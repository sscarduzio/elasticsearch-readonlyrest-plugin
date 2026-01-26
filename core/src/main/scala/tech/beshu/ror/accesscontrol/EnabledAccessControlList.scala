/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.accesscontrol

import cats.data.{NonEmptyList, NonEmptySet, WriterT}
import cats.implicits.*
import monix.eval.Task
import tech.beshu.ror.accesscontrol.AccessControlList.*
import tech.beshu.ror.accesscontrol.AccessControlList.UserMetadataRequestResult.*
import tech.beshu.ror.accesscontrol.EnabledAccessControlList.AccessControlListStaticContext
import tech.beshu.ror.accesscontrol.blocks.Block.ExecutionResult.{Matched, Mismatched}
import tech.beshu.ror.accesscontrol.blocks.Block.{ExecutionResult, History, HistoryItem, Policy}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.UserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata.WithGroups
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata.WithGroups.GroupMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthenticationRule, AuthorizationRule}
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.FieldsRule
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.RorKbnLicenseType.{Enterprise, Free, Pro}
import tech.beshu.ror.accesscontrol.domain.{Group, Header}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.orders.forbiddenCauseOrder
import tech.beshu.ror.accesscontrol.request.UserMetadataRequestContext.UserMetadataApiVersion
import tech.beshu.ror.accesscontrol.request.{RequestContext, UserMetadataRequestContext}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

import scala.collection.View

class EnabledAccessControlList(val blocks: NonEmptyList[Block],
                               override val staticContext: AccessControlListStaticContext)
  extends AccessControlList {

  override val description: String = "Enabled ROR ACL"

  override def handleRegularRequest[B <: BlockContext : BlockContextUpdater](context: RequestContext.Aux[B]): Task[WithHistory[RegularRequestResult[B], B]] = {
    blocks
      .tail
      .foldLeft(checkBlock(blocks.head, context)) { case (currentResult, block) =>
        for {
          prevBlocksExecutionResult <- currentResult
          newCurrentResult <- prevBlocksExecutionResult match {
            case Mismatched(_) =>
              checkBlock(block, context)
            case Matched(_, _) =>
              lift(prevBlocksExecutionResult)
          }
        } yield newCurrentResult
      }
      .run
      .map { case (history, result) =>
        val res: RegularRequestResult[B] = result match {
          case Matched(block, blockContext) =>
            block.policy match {
              case Policy.Allow => RegularRequestResult.Allow(blockContext, block)
              case Policy.Forbid(_) => RegularRequestResult.ForbiddenBy(blockContext, block)
            }
          case Mismatched(_) if wasRejectedDueToAliasNotFound(history) =>
            RegularRequestResult.AliasNotFound()
          case Mismatched(_) if wasRejectedDueToTemplateNotFound(history) =>
            RegularRequestResult.TemplateNotFound()
          case Mismatched(_) =>
            wasRejectedDueToIndexNotFound(history) match {
              case Some(error) =>
                RegularRequestResult.IndexNotFound(error.allowedClusters)
              case None =>
                RegularRequestResult.ForbiddenByMismatched(
                  nonEmptySetOfMismatchedCausesFromHistory(history)
                )
            }
        }
        WithHistory[RegularRequestResult[B], B](history, res)
      }
      .onErrorHandle { ex =>
        WithHistory(Vector.empty, RegularRequestResult.Failed(ex))
      }
  }

  override def handleMetadataRequest(context: UserMetadataRequestContext.Aux[UserMetadataRequestBlockContext]): Task[WithHistory[UserMetadataRequestResult, UserMetadataRequestBlockContext]] = {
    Task
      .parSequence(blocks.toList.map(executeBlocksForUserMetadata(_, context)))
      .map(_.flatten)
      .map { blockResults =>
        val (executionResults, history) = blockResults.unzip
        val matchedResults = executionResults.view.onlyMatched()
        val result = context.apiVersion match {
          case UserMetadataApiVersion.V1 =>
            determineUserMetadataForApiV1(matchedResults, context.currentGroupId, history)
          case UserMetadataApiVersion.V2(Free | Pro | Enterprise(false)) =>
            determineUserMetadataForApiV2WithoutTenancyHandling(matchedResults, history)
          case UserMetadataApiVersion.V2(Enterprise(true)) =>
            determineUserMetadataForApiV2WithTenancyHandling(matchedResults, history)
        }
        WithHistory(history.toVector, result)
      }
  }

  private def determineUserMetadataForApiV1(matched: Iterable[Matched[UserMetadataRequestBlockContext]],
                                            optPreferredGroupId: Option[GroupId],
                                            history: Iterable[History[UserMetadataRequestBlockContext]]) = {
    val result = determineUserMetadata(matched, history, ignoreGroupsHandling = false)
    (result, optPreferredGroupId) match {
      case (Allow(_: UserMetadata.WithoutGroups), Some(currentGroupId)) =>
        createForbiddenByMismatchedResult(history)
      case (Allow(withGroups@UserMetadata.WithGroups(groupsMetadata)), Some(currentGroupId)) =>
        determineUserMetadataForCurrentGroup(withGroups, currentGroupId, history)
      case (allow@Allow(UserMetadata.WithoutGroups(_, _, _, block, blockContext)), None) =>
        block.policy match {
          case Policy.Allow => allow
          case Policy.Forbid(_) => ForbiddenBy(blockContext, block)
        }
      case (Allow(withGroups@UserMetadata.WithGroups(groupsMetadata)), None) =>
        determineUserMetadataForFirstAllowedGroup(withGroups, history)
      case _ =>
        result
    }
  }

  private def determineUserMetadataForApiV2WithoutTenancyHandling(matched: Iterable[Matched[UserMetadataRequestBlockContext]],
                                                                  history: Iterable[History[UserMetadataRequestBlockContext]]) = {
    determineUserMetadata(matched, history, ignoreGroupsHandling = true)
  }

  private def determineUserMetadataForApiV2WithTenancyHandling(matched: Iterable[Matched[UserMetadataRequestBlockContext]],
                                                               history: Iterable[History[UserMetadataRequestBlockContext]]) = {
    determineUserMetadata(matched, history, false) match {
      case allow@Allow(UserMetadata.WithoutGroups(_, _, _, block, blockContext)) =>
        block.policy match {
          case Policy.Allow => allow
          case Policy.Forbid(_) => ForbiddenBy(blockContext, block)
        }
      case Allow(withGroups@UserMetadata.WithGroups(groupsMetadata)) =>
        determineUserMetadataForFirstAllowedGroup(withGroups, history)
      case result =>
        result
    }
  }

  private def determineUserMetadataForCurrentGroup(userMetadata: WithGroups,
                                                   currentGroupId: GroupId,
                                                   history: Iterable[History[UserMetadataRequestBlockContext]]) = {
    userMetadata.groupsMetadata.get(currentGroupId) match {
      case Some(groupMetadata) =>
        groupMetadata.block.policy match {
          case Policy.Allow =>
            userMetadata
              .excludeOtherThanAllowTypeGroups().map(Allow.apply)
              .getOrElse(createForbiddenByMismatchedResult(history))
          case Policy.Forbid(_) =>
            createForbiddenBy(groupMetadata)
        }
      case None =>
        createForbiddenByMismatchedResult(history)
    }
  }

  private def determineUserMetadataForFirstAllowedGroup(userMetadata: WithGroups,
                                                        history: Iterable[History[UserMetadataRequestBlockContext]]) = {
    userMetadata.groupsMetadata.values.find(_.block.policy == Policy.Allow) match {
      case Some(groupMetadata) =>
        userMetadata
          .excludeOtherThanAllowTypeGroups().map(Allow.apply)
          .getOrElse(createForbiddenByMismatchedResult(history))
      case None =>
        createForbiddenBy(userMetadata.groupsMetadata.values.head)
    }
  }

  private def determineUserMetadata(matched: Iterable[Matched[UserMetadataRequestBlockContext]],
                                    history: Iterable[History[UserMetadataRequestBlockContext]],
                                    ignoreGroupsHandling: Boolean): UserMetadataRequestResult = {
    val withLoggedUsers = matched.view.onlyLoggedUsers()
    lazy val withGroups = withLoggedUsers.onlyWithAvailableGroups()

    if (ignoreGroupsHandling || withGroups.isEmpty) {
      val matchedBlock = withLoggedUsers
        .allowedThroughFirstForbidden()
        .firstAllowedWithKibanaIndexOrHead()

      matchedBlock match {
        case Some(matched) if matched.block.policy == Policy.Allow =>
          createAllowResult(matched)
        case Some(_) | None =>
          createForbiddenResult(matched, history)
      }
    } else {
      val groupsMetadata = withGroups
        .gatherGroupMetadataPreservingOrder()
        .groupByOrdered(_.group)
        .flatMap { case (group, metadataCollection) =>
          metadataCollection
            .allowedThroughFirstForbidden()
            .firstAllowedWithKibanaIndexOrHead()
            .map(group -> _)
        }
        .values
        .toList

      NonEmptyList.fromList(groupsMetadata) match {
        case Some(nel) => createAllowResult(nel)
        case None => createForbiddenResult(matched, history)
      }
    }
  }

  extension [T <: ExecutionResult[UserMetadataRequestBlockContext]](blockResults: View[T]) {
    private def onlyMatched(): View[Matched[UserMetadataRequestBlockContext]] = {
      blockResults.collect { case m@Matched(_, blockContext) => m }
    }

    private def onlyLoggedUsers(): View[T] = {
      blockResults.filter(_.blockContext.blockMetadata.loggedUser.isDefined)
    }

    private def onlyWithAvailableGroups(): View[T] = {
      blockResults.takeWhile(_.blockContext.blockMetadata.availableGroups.nonEmpty)
    }
  }

  extension (blockResults: View[Matched[UserMetadataRequestBlockContext]]) {

    private def gatherGroupMetadataPreservingOrder(): Seq[GroupMetadata] = {
      blockResults.foldLeft(Vector.empty[GroupMetadata]) { case (acc, matched) =>
        matched.blockContext.blockMetadata.availableGroups
          .foldLeft(acc) { case (acc, group) =>
            acc :+ groupMetadataFrom(group, matched)
          }
      }
    }

    private def allowedThroughFirstForbidden(): View[Matched[UserMetadataRequestBlockContext]] = {
      val (allowed, rest) = blockResults.span(_.block.policy == Policy.Allow)
      allowed ++ rest.headOption.toList
    }

    private def firstAllowedWithKibanaIndexOrHead(): Option[Matched[UserMetadataRequestBlockContext]] = {
      blockResults
        .find(m => m.block.policy == Policy.Allow && m.blockContext.blockMetadata.kibanaMetadata.flatMap(_.index).isDefined)
        .orElse(blockResults.headOption)
    }
  }

  extension (metadata: Iterable[GroupMetadata]) {
    private def allowedThroughFirstForbidden(): Iterable[GroupMetadata] = {
      val (allowed, rest) = metadata.span(_.block.policy == Policy.Allow)
      allowed ++ rest.headOption.toList
    }

    private def firstAllowedWithKibanaIndexOrHead(): Option[GroupMetadata] = {
      metadata
        .find(m => m.block.policy == Policy.Allow && m.kibanaMetadata.flatMap(_.index).isDefined)
        .orElse(metadata.headOption)
    }
  }

  private def groupMetadataFrom(group: Group, matchedBlock: Matched[UserMetadataRequestBlockContext]) = {
    val blockMetadata = matchedBlock.blockContext.blockMetadata
    GroupMetadata(
      group,
      blockMetadata.loggedUser.get, // we are sure there is a user defined at this place
      blockMetadata.userOrigin,
      blockMetadata.kibanaMetadata,
      matchedBlock.block,
      matchedBlock.blockContext
    )
  }

  private def createAllowResult(groupsMetadata: NonEmptyList[GroupMetadata]) = {
    Allow(UserMetadata.WithGroups(groupsMetadata))
  }

  private def createAllowResult(matchedBlock: Matched[UserMetadataRequestBlockContext]) = {
    Allow {
      val matchedBlockMetadata = matchedBlock.blockContext.blockMetadata
      UserMetadata.WithoutGroups(
        matchedBlockMetadata.loggedUser.get, // we are sure there is a user defined at this place
        matchedBlockMetadata.userOrigin,
        matchedBlockMetadata.kibanaMetadata,
        matchedBlock.block,
        matchedBlock.blockContext
      )
    }
  }

  private def createForbiddenResult(blockResults: Iterable[ExecutionResult[UserMetadataRequestBlockContext]],
                                    history: Iterable[History[UserMetadataRequestBlockContext]]) = {
    val matchedForbidBlock = blockResults.toList.collectFirstSome {
      case m@Matched(block, _) => block.policy match {
        case Policy.Allow => None
        case Policy.Forbid(_) => Some(m)
      }
      case Mismatched(_) => None
    }
    matchedForbidBlock match {
      case Some(Matched(block, blockContext)) => ForbiddenBy(blockContext, block)
      case None => ForbiddenByMismatched(nonEmptySetOfMismatchedCausesFromHistory(history))
    }
  }

  private def createForbiddenBy(groupMetadata: GroupMetadata) = {
    ForbiddenBy(groupMetadata.blockContext, groupMetadata.block)
  }

  private def createForbiddenByMismatchedResult(history: Iterable[History[UserMetadataRequestBlockContext]]) = {
    ForbiddenByMismatched(nonEmptySetOfMismatchedCausesFromHistory(history))
  }

  private def executeBlocksForUserMetadata(block: Block,
                                           context: RequestContext.Aux[UserMetadataRequestBlockContext]) = {
    block
      .execute(context)
      .map(Some.apply)
      .onErrorRecover { case _ => None }
  }

  private def checkBlock[B <: BlockContext : BlockContextUpdater](block: Block,
                                                                  requestContent: RequestContext.Aux[B]): WriterT[Task, Vector[History[B]], ExecutionResult[B]] = {
    WriterT.apply {
      block
        .execute(requestContent)
        .map { case (r, history) => (Vector(history), r) }
    }
  }

  private def lift[B <: BlockContext](executionResult: ExecutionResult[B]): WriterT[Task, Vector[History[B]], ExecutionResult[B]] = {
    WriterT.value[Task, Vector[History[B]], ExecutionResult[B]](executionResult)
  }

  private def nonEmptySetOfMismatchedCausesFromHistory[B <: BlockContext](history: Iterable[History[B]]): NonEmptySet[ForbiddenCause] = {
    val causes = rejectionsFrom(history).map {
      case Rejected(None) | Rejected(Some(Rejected.Cause.IndexNotFound(_) | Rejected.Cause.AliasNotFound | Rejected.Cause.TemplateNotFound)) =>
        ForbiddenCause.OperationNotAllowed
      case Rejected(Some(Rejected.Cause.ImpersonationNotAllowed)) =>
        ForbiddenCause.ImpersonationNotAllowed
      case Rejected(Some(Rejected.Cause.ImpersonationNotSupported)) =>
        ForbiddenCause.ImpersonationNotSupported
    }
    NonEmptyList
      .fromList(causes.toList)
      .getOrElse(NonEmptyList.one(ForbiddenCause.OperationNotAllowed))
      .toNes
  }

  private def wasRejectedDueToIndexNotFound[B <: BlockContext](history: Vector[History[B]]): Option[Rejected.Cause.IndexNotFound] = {
    val rejections = rejectionsFrom(history)
    if (impersonationRejectionExists(rejections)) {
      None
    } else {
      indexNotFoundRejectionExists(rejections)
    }
  }

  private def wasRejectedDueToAliasNotFound[B <: BlockContext](history: Vector[History[B]]) = {
    val rejections = rejectionsFrom(history)
    !impersonationRejectionExists(rejections) && aliasNotFoundRejectionExists(rejections)
  }

  private def wasRejectedDueToTemplateNotFound[B <: BlockContext](history: Vector[History[B]]) = {
    val rejections = rejectionsFrom(history)
    !impersonationRejectionExists(rejections) && templateNotFoundRejectionExists(rejections)
  }

  private def indexNotFoundRejectionExists(rejections: Vector[Rejected[_]]): Option[Rejected.Cause.IndexNotFound] = {
    rejections.collectFirst {
      case Rejected(Some(error@Rejected.Cause.IndexNotFound(_))) => error
    }
  }

  private def aliasNotFoundRejectionExists(rejections: Vector[Rejected[_]]) = {
    rejections.exists {
      case Rejected(Some(Rejected.Cause.AliasNotFound)) => true
      case Rejected(Some(Rejected.Cause.IndexNotFound(_))) => false
      case Rejected(Some(Rejected.Cause.TemplateNotFound)) => false
      case Rejected(None) => false
      case Rejected(Some(Rejected.Cause.ImpersonationNotAllowed)) => false
      case Rejected(Some(Rejected.Cause.ImpersonationNotSupported)) => false
    }
  }

  private def templateNotFoundRejectionExists(rejections: Vector[Rejected[_]]) = {
    rejections.exists {
      case Rejected(Some(Rejected.Cause.AliasNotFound)) => false
      case Rejected(Some(Rejected.Cause.IndexNotFound(_))) => false
      case Rejected(Some(Rejected.Cause.TemplateNotFound)) => true
      case Rejected(None) => false
      case Rejected(Some(Rejected.Cause.ImpersonationNotAllowed)) => false
      case Rejected(Some(Rejected.Cause.ImpersonationNotSupported)) => false
    }
  }

  private def impersonationRejectionExists(rejections: Vector[Rejected[_]]) = {
    rejections.exists {
      case Rejected(Some(Rejected.Cause.IndexNotFound(_))) => false
      case Rejected(Some(Rejected.Cause.AliasNotFound)) => false
      case Rejected(Some(Rejected.Cause.TemplateNotFound)) => false
      case Rejected(None) => false
      case Rejected(Some(Rejected.Cause.ImpersonationNotAllowed)) => true
      case Rejected(Some(Rejected.Cause.ImpersonationNotSupported)) => true
    }
  }

  private def rejectionsFrom[B <: BlockContext](history: Iterable[History[B]]): Vector[Rejected[B]] = {
    history
      .flatMap {
        _.items
          .collect { case h: HistoryItem.RuleHistoryItem[B] => h.result }
          .collect { case r: Rejected[B] => r }
      }
      .toVector
  }
}

object EnabledAccessControlList {

  final class AccessControlListStaticContext(blocks: NonEmptyList[Block],
                                             globalSettings: GlobalSettings,
                                             override val obfuscatedHeaders: Set[Header.Name])
    extends AccessControlStaticContext {

    override val forbiddenRequestMessage: String = globalSettings.forbiddenRequestMessage

    val usedFlsEngineInFieldsRule: Option[GlobalSettings.FlsEngine] = {
      blocks
        .flatMap(_.rules)
        .collect {
          case rule: FieldsRule => rule.settings.flsEngine
        }
        .headOption
    }

    val doesRequirePassword: Boolean = {
      globalSettings.showBasicAuthPrompt &&
        blocks
          .find(_
            .rules
            .collect {
              case _: AuthenticationRule => true
              case _: AuthorizationRule => true
            }
            .nonEmpty
          )
          .isDefined
    }
  }
}

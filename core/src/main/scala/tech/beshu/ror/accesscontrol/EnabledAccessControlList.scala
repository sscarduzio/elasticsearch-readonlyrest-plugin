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

import cats.data.{NonEmptyList, WriterT}
import cats.implicits.*
import monix.eval.Task
import monix.execution.Scheduler
import tech.beshu.ror.accesscontrol.AccessControlList.*
import tech.beshu.ror.accesscontrol.AccessControlList.UserMetadataRequestResult.*
import tech.beshu.ror.accesscontrol.EnabledAccessControlList.AccessControlListStaticContext
import tech.beshu.ror.accesscontrol.History.BlockHistory
import tech.beshu.ror.accesscontrol.blocks.Block.Policy
import tech.beshu.ror.accesscontrol.blocks.BlockContext.UserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.Decision.{Denied, Permitted}
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata.WithGroups.GroupMetadata
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata.{MetadataOrigin, WithGroups}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthenticationRule, AuthorizationRule}
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.FieldsRule
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, BlockContextUpdater, Decision}
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.RorKbnLicenseType.{Enterprise, Free, Pro}
import tech.beshu.ror.accesscontrol.domain.{Group, Header, LoggedUser}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.request.{RequestContext, UserMetadataRequestContext}
import tech.beshu.ror.accesscontrol.request.UserMetadataRequestContext.UserMetadataApiVersion
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged
import tech.beshu.ror.utils.ScalaOps.*

import scala.collection.View
import scala.collection.immutable.ListMap

class EnabledAccessControlList(val blocks: NonEmptyList[Block],
                               override val staticContext: AccessControlListStaticContext)
                              (implicit scheduler: Scheduler)
  extends AccessControlList {

  override val description: String = "Enabled ROR ACL"

  override def handleRegularRequest[B <: BlockContext : BlockContextUpdater](context: RequestContext.Aux[B]): Task[(RegularRequestResult[B], History[B])] =
    doPrivileged {
      blocks
        .tail
        .foldLeft(executeBlocksForRegularRequest(blocks.head, context)) { case (currentResult, block) =>
          for {
            prevBlocksExecutionResult <- currentResult
            newCurrentResult <- prevBlocksExecutionResult match {
              case Decision.Denied(_) =>
                executeBlocksForRegularRequest(block, context)
              case Decision.Permitted(_) =>
                lift(prevBlocksExecutionResult)
            }
          } yield newCurrentResult
        }
        .run
        .map { case (blocksHistory, result) =>
          val handlingResult: RegularRequestResult[B] = result match {
            case Decision.Permitted(blockContext) =>
              blockContext.block.policy match {
                case Policy.Allow => RegularRequestResult.Allowed(blockContext)
                case Policy.Forbid(_) => RegularRequestResult.Forbidden(blockContext)
              }
            case Decision.Denied(_) =>
              val denialCauses = denialCausesFrom(blocksHistory)
              val noImpersonation = !impersonationRelatedCauseExists(denialCauses)
              if (noImpersonation && aliasNotFoundCauseExists(denialCauses))
                RegularRequestResult.AliasNotFound()
              else if (noImpersonation && templateNotFoundCauseExists(denialCauses))
                RegularRequestResult.TemplateNotFound()
              else if (noImpersonation) indexNotFoundCauseExists(denialCauses) match {
                case Some(error) =>
                  RegularRequestResult.IndexNotFound(error.allowedClusters)
                case None =>
                  RegularRequestResult.ForbiddenByMismatched(denyCausesPerBlockFrom(blocksHistory))
              }
              else
                RegularRequestResult.ForbiddenByMismatched(denyCausesPerBlockFrom(blocksHistory))
          }
          handlingResult -> History(blocksHistory)
        }
        .onErrorHandle { ex =>
          RegularRequestResult.Failed(ex) -> History.empty
        }
    }

  override def handleMetadataRequest(context: UserMetadataRequestContext.Aux[UserMetadataRequestBlockContext]): Task[(UserMetadataRequestResult, History[UserMetadataRequestBlockContext])] =
    doPrivileged {
      if (staticContext.doesRequirePassword) {
        Task.delay((UserMetadataRequestResult.RorKbnPluginNotSupported, History.empty))
      } else {
        Task
          .parSequence(blocks.toList.map(executeBlocksForUserMetadata(_, context)))
          .map(_.flatten)
          .map { blockResults =>
            val (executionResults, blocksHistory) = blockResults.unzip
            val history = History(blocksHistory.toVector)
            val matchedResults = executionResults.view.onlyMatched()
            val handlingResult = context.apiVersion match {
              case UserMetadataApiVersion.V1 =>
                determineUserMetadataForApiV1(matchedResults, context.currentGroupId, history)
              case UserMetadataApiVersion.V2(Free | Pro | Enterprise(false)) =>
                determineUserMetadataForApiV2WithoutTenancyHandling(matchedResults, history)
              case UserMetadataApiVersion.V2(Enterprise(true)) =>
                determineUserMetadataForApiV2WithTenancyHandling(matchedResults, history)
            }
            handlingResult -> history
          }
      }
    }

  private def determineUserMetadataForApiV1(matched: Iterable[Permitted[UserMetadataRequestBlockContext]],
                                            optPreferredGroupId: Option[GroupId],
                                            history: History[UserMetadataRequestBlockContext]) = {
    val result = determineUserMetadata(matched, history, ignoreGroupsHandling = false)
    (result, optPreferredGroupId) match {
      case (Allowed(_: UserMetadata.WithoutGroups), Some(currentGroupId)) =>
        createForbiddenByMismatchedResult(history)
      case (Allowed(withGroups@UserMetadata.WithGroups(groupsMetadata)), Some(currentGroupId)) =>
        determineUserMetadataForCurrentGroup(withGroups, currentGroupId, history)
      case (allow@Allowed(UserMetadata.WithoutGroups(_, _, _, MetadataOrigin(blockContext))), None) =>
        blockContext.block.policy match {
          case Policy.Allow => allow
          case Policy.Forbid(_) => Forbidden(blockContext)
        }
      case (Allowed(withGroups@UserMetadata.WithGroups(groupsMetadata)), None) =>
        determineUserMetadataForFirstAllowedGroup(withGroups, history)
      case _ =>
        result
    }
  }

  private def determineUserMetadataForApiV2WithoutTenancyHandling(matched: Iterable[Permitted[UserMetadataRequestBlockContext]],
                                                                  history: History[UserMetadataRequestBlockContext]) = {
    determineUserMetadata(matched, history, ignoreGroupsHandling = true)
  }

  private def determineUserMetadataForApiV2WithTenancyHandling(matched: Iterable[Permitted[UserMetadataRequestBlockContext]],
                                                               history: History[UserMetadataRequestBlockContext]) = {
    determineUserMetadata(matched, history, ignoreGroupsHandling = false) match {
      case allow@Allowed(UserMetadata.WithoutGroups(_, _, _, MetadataOrigin(blockContext))) =>
        blockContext.block.policy match {
          case Policy.Allow => allow
          case Policy.Forbid(_) => Forbidden(blockContext)
        }
      case Allowed(withGroups@UserMetadata.WithGroups(groupsMetadata)) =>
        determineUserMetadataForFirstAllowedGroup(withGroups, history)
      case result =>
        result
    }
  }

  private def determineUserMetadataForCurrentGroup(userMetadata: WithGroups,
                                                   currentGroupId: GroupId,
                                                   history: History[UserMetadataRequestBlockContext]) = {
    userMetadata.groupsMetadata.get(currentGroupId) match {
      case Some(groupMetadata) =>
        if (groupMetadata.isAllowed) {
          userMetadata
            .excludeOtherThanAllowTypeGroups().map(Allowed.apply)
            .getOrElse(createForbiddenByMismatchedResult(history))
        } else {
          createForbiddenBy(groupMetadata)
        }
      case None =>
        createForbiddenByMismatchedResult(history)
    }
  }

  private def determineUserMetadataForFirstAllowedGroup(userMetadata: WithGroups,
                                                        history: History[UserMetadataRequestBlockContext]) = {
    userMetadata.groupsMetadata.values.find(_.isAllowed) match {
      case Some(groupMetadata) =>
        userMetadata
          .excludeOtherThanAllowTypeGroups().map(Allowed.apply)
          .getOrElse(createForbiddenByMismatchedResult(history))
      case None =>
        createForbiddenBy(userMetadata.groupsMetadata.values.head)
    }
  }

  private def determineUserMetadata(matched: Iterable[Permitted[UserMetadataRequestBlockContext]],
                                    history: History[UserMetadataRequestBlockContext],
                                    ignoreGroupsHandling: Boolean): UserMetadataRequestResult = {
    val withLoggedUsers = matched.view.onlyLoggedUsers()
    lazy val withGroups = withLoggedUsers.onlyWithAvailableGroups()

    if (ignoreGroupsHandling || withGroups.isEmpty) {
      val firstFittingMatch = withLoggedUsers
        .allowedThroughFirstForbidden()
        .firstAllowedWithKibanaIndexOrHead()

      firstFittingMatch match {
        case Some(m) if m.result.context._1.policy == Policy.Allow =>
          createAllowResult(m)
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

  extension (blockResults: View[Decision[UserMetadataRequestBlockContext]]) {

    private def onlyMatched(): View[Permitted[UserMetadataRequestBlockContext]] = {
      blockResults.collect { case m@Permitted(blockContext) => m }
    }
  }

  extension (blockResults: View[Permitted[UserMetadataRequestBlockContext]]) {

    private def onlyLoggedUsers(): View[PermittedWithUser[UserMetadataRequestBlockContext]] = {
      blockResults.flatMap { r =>
        r.context
          .blockMetadata.loggedUser
          .map(user => PermittedWithUser(r, user))
      }
    }
  }

  extension (blockResults: View[PermittedWithUser[UserMetadataRequestBlockContext]]) {

    private def onlyWithAvailableGroups(): View[PermittedWithUser[UserMetadataRequestBlockContext]] = {
      blockResults
        .takeWhile(_.result.context.blockMetadata.availableGroups.nonEmpty)
    }

    private def gatherGroupMetadataPreservingOrder(): Seq[GroupMetadata] = {
      blockResults
        .flatMap { matched => matched.result.context.blockMetadata.availableGroups.map(groupMetadataFrom(_, matched)) }
        .toSeq
    }

    private def allowedThroughFirstForbidden(): View[PermittedWithUser[UserMetadataRequestBlockContext]] = {
      val (allowed, rest) = blockResults.span(_.result.context.block.policy == Policy.Allow)
      allowed ++ rest.headOption.toList
    }

    private def firstAllowedWithKibanaIndexOrHead(): Option[PermittedWithUser[UserMetadataRequestBlockContext]] = {
      blockResults
        .find { m =>
          val context = m.result.context
          context.block.policy == Policy.Allow && context.blockMetadata.kibanaPolicy.flatMap(_.index).isDefined
        }
        .orElse(blockResults.headOption)
    }
  }

  extension (metadata: Iterable[GroupMetadata]) {
    private def allowedThroughFirstForbidden(): Iterable[GroupMetadata] = {
      val (allowed, rest) = metadata.span(_.metadataOrigin.blockContext.block.policy == Policy.Allow)
      allowed ++ rest.headOption.toList
    }

    private def firstAllowedWithKibanaIndexOrHead(): Option[GroupMetadata] = {
      metadata
        .find(m => m.metadataOrigin.blockContext.block.policy == Policy.Allow && m.kibanaPolicy.flatMap(_.index).isDefined)
        .orElse(metadata.headOption)
    }
  }

  private final case class PermittedWithUser[T <: BlockContext](result: Permitted[T], loggedUser: LoggedUser)

  private def groupMetadataFrom(group: Group, permitted: PermittedWithUser[UserMetadataRequestBlockContext]) = {
    val context = permitted.result.context
    val blockMetadata = context.blockMetadata
    GroupMetadata(
      group,
      permitted.loggedUser,
      blockMetadata.userOrigin,
      blockMetadata.kibanaPolicy,
      MetadataOrigin(context)
    )
  }

  private def createAllowResult(groupsMetadata: NonEmptyList[GroupMetadata]) = {
    Allowed(UserMetadata.WithGroups(groupsMetadata))
  }

  private def createAllowResult(permitted: PermittedWithUser[UserMetadataRequestBlockContext]) = {
    Allowed {
      val context = permitted.result.context
      val blockMetadata = context.blockMetadata
      UserMetadata.WithoutGroups(
        permitted.loggedUser,
        blockMetadata.userOrigin,
        blockMetadata.kibanaPolicy,
        MetadataOrigin(context)
      )
    }
  }

  private def createForbiddenResult(blockResults: Iterable[Decision[UserMetadataRequestBlockContext]],
                                    history: History[UserMetadataRequestBlockContext]) = {
    val matchedForbidBlock = blockResults.toList.collectFirstSome {
      case m@Permitted(blockContext) => blockContext.block.policy match {
        case Policy.Allow => None
        case Policy.Forbid(_) => Some(m)
      }
      case Denied(_) => None
    }
    matchedForbidBlock match {
      case Some(Permitted(blockContext)) => Forbidden(blockContext)
      case None => createForbiddenByMismatchedResult(history)
    }
  }

  private def createForbiddenBy(groupMetadata: GroupMetadata) = {
    Forbidden(groupMetadata.metadataOrigin.blockContext)
  }

  private def createForbiddenByMismatchedResult(history: History[UserMetadataRequestBlockContext]) =
    ForbiddenByMismatched {
      history.blocks.foldLeft(ListMap.empty[Block.Name, Denied.Cause]) {
        case (acc, BlockHistory.Denied(block, decision, _)) =>
          acc + (block.name -> decision.cause)
        case (acc, BlockHistory.Permitted(_, _, _)) =>
          acc
      }
    }

  private def executeBlocksForUserMetadata(block: Block,
                                           requestContext: RequestContext.Aux[UserMetadataRequestBlockContext]) = {
    block
      .evaluate(requestContext)
      .map(Some.apply)
      .onErrorRecover { case _ => None }
  }

  private def executeBlocksForRegularRequest[B <: BlockContext : BlockContextUpdater](block: Block,
                                                                                      requestContext: RequestContext.Aux[B]): WriterT[Task, Vector[BlockHistory[B]], Decision[B]] = {
    for {
      blockEvalDecision <- WriterT.liftF(block.evaluate(requestContext))
      (decision, history) = blockEvalDecision
      aclProcessingResult <- lift(decision).tell(Vector(history))
    } yield aclProcessingResult
  }

  private def lift[B <: BlockContext](result: Decision[B]): WriterT[Task, Vector[BlockHistory[B]], Decision[B]] = {
    WriterT.value[Task, Vector[BlockHistory[B]], Decision[B]](result)
  }

  private def denyCausesPerBlockFrom(history: Iterable[BlockHistory[_]]): ListMap[Block.Name, Denied.Cause] = {
    ListMap.from {
      history.flatMap {
        case BlockHistory.Permitted(_, _, _) => None
        case BlockHistory.Denied(block, decision, _) => Some(block.name -> decision.cause)
      }
    }
  }

  private def indexNotFoundCauseExists(causes: Set[Denied.Cause]): Option[Denied.Cause.IndexNotFound] = {
    causes.collectFirst {
      case cause@Denied.Cause.IndexNotFound(_) => cause
    }
  }

  private def aliasNotFoundCauseExists(causes: Set[Denied.Cause]) = {
    causes.exists {
      case Denied.Cause.AliasNotFound => true
      case _: Denied.Cause.OtherFailure => false
      case _: Denied.Cause.AuthenticationFailure => false
      case _: Denied.Cause.AuthorizationFailure => false
    }
  }

  private def templateNotFoundCauseExists(causes: Set[Denied.Cause]) = {
    causes.exists {
      case Denied.Cause.TemplateNotFound => true
      case _: Denied.Cause.OtherFailure => false
      case _: Denied.Cause.AuthenticationFailure => false
      case _: Denied.Cause.AuthorizationFailure => false
    }
  }

  private def impersonationRelatedCauseExists(causes: Set[Denied.Cause]) = {
    causes.exists {
      case Denied.Cause.ImpersonationNotAllowed => true
      case Denied.Cause.ImpersonationNotSupported => true
      case _: Denied.Cause.OtherFailure => false
      case _: Denied.Cause.AuthenticationFailure => false
      case _: Denied.Cause.AuthorizationFailure => false
    }
  }

  private def denialCausesFrom[B <: BlockContext](history: Iterable[BlockHistory[B]]): Set[Denied.Cause] = {
    history
      .flatMap {
        case BlockHistory.Permitted(_, _, _) =>
          Iterable.empty
        case BlockHistory.Denied(_, _, history) =>
          history.flatMap {
            _.decision match {
              case Decision.Permitted(_) => None
              case Denied(cause) => Some(cause)
            }
          }
      }
      .toCovariantSet
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

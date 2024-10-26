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
import tech.beshu.ror.accesscontrol.EnabledAccessControlList.AccessControlListStaticContext
import tech.beshu.ror.accesscontrol.blocks.Block.ExecutionResult.{Matched, Mismatched}
import tech.beshu.ror.accesscontrol.blocks.Block.{ExecutionResult, History, HistoryItem, Policy}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthenticationRule, AuthorizationRule}
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.FieldsRule
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.{Group, Header}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.orders.forbiddenCauseOrder
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.utils.uniquelist.UniqueList

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
          case Mismatched(_) if wasRejectedDueToIndexNotFound(history) =>
            RegularRequestResult.IndexNotFound()
          case Mismatched(_) if wasRejectedDueToAliasNotFound(history) =>
            RegularRequestResult.AliasNotFound()
          case Mismatched(_) if wasRejectedDueToTemplateNotFound(history) =>
            RegularRequestResult.TemplateNotFound()
          case Mismatched(_) =>
            RegularRequestResult.ForbiddenByMismatched(
              nonEmptySetOfMismatchedCausesFromHistory(history)
            )
        }
        WithHistory[RegularRequestResult[B], B](history, res)
      }
      .onErrorHandle { ex =>
        WithHistory(Vector.empty, RegularRequestResult.Failed(ex))
      }
  }

  override def handleMetadataRequest(context: RequestContext.Aux[CurrentUserMetadataRequestBlockContext]): Task[WithHistory[UserMetadataRequestResult, CurrentUserMetadataRequestBlockContext]] = {
    Task
      .parSequence(blocks.toList.map(executeBlocksForUserMetadata(_, context)))
      .map(_.flatten)
      .map { blockResults =>
        val history = blockResults.map(_._2).toVector
        val result = matchedAllowedBlocks(blockResults.map(_._1)) match {
          case Right(matchedResults) =>
            userMetadataFrom(matchedResults, context.initialBlockContext.userMetadata.currentGroupId) match {
              case Some((userMetadata, matchedBlock)) => UserMetadataRequestResult.Allow(userMetadata, matchedBlock)
              case None => UserMetadataRequestResult.Forbidden(nonEmptySetOfMismatchedCausesFromHistory(history))
            }
          case Left(_) =>
            UserMetadataRequestResult.Forbidden(nonEmptySetOfMismatchedCausesFromHistory(history))
        }
        WithHistory(history, result)
      }
  }

  private def userMetadataFrom(matchedResults: NonEmptyList[Matched[CurrentUserMetadataRequestBlockContext]],
                               optPreferredGroupId: Option[GroupId]): Option[(UserMetadata, Block)] = {
    optPreferredGroupId match {
      case Some(preferredGroupId) =>
        matchedResults
          .toList
          .flatMap { case result@Matched(_, bc) =>
            bc.userMetadata.availableGroups.find(_.id == preferredGroupId)
              .map(preferredGroup => (result, preferredGroup))
          }
          .headOption
          .map { case (result, preferredGroup) =>
            val userMetadata =
              updateUserMetadataGroups(result.blockContext, Some(preferredGroup), allAvailableGroupsFrom(matchedResults))
            (userMetadata, result.block)
          }
      case None =>
        Some {
          val Matched(block, bc) = matchedResults.head
          val userMetadata = updateUserMetadataGroups(bc, None, allAvailableGroupsFrom(matchedResults))
          (userMetadata, block)
        }
    }
  }

  private def allAvailableGroupsFrom(matchedResults: NonEmptyList[Matched[CurrentUserMetadataRequestBlockContext]]) = {
    UniqueList.fromIterable(
      matchedResults.toList.flatMap {
        case Matched(_, bc) => bc.userMetadata.availableGroups
      }
    )
  }

  private def updateUserMetadataGroups(blockContext: CurrentUserMetadataRequestBlockContext,
                                       currentGroup: Option[Group],
                                       availableGroups: UniqueList[Group]) = {
    currentGroup
      .foldLeft(blockContext.userMetadata.withAvailableGroups(availableGroups)) {
        case (userMetadata, group) => userMetadata.withCurrentGroup(group)
      }
  }

  private def executeBlocksForUserMetadata(block: Block,
                                           context: RequestContext.Aux[CurrentUserMetadataRequestBlockContext]) = {
    block
      .execute(context)
      .map(Some.apply)
      .onErrorRecover { case _ => None }
  }

  private def matchedAllowedBlocks[B <: BlockContext](blockResults: List[Block.ExecutionResult[B]]) = {
    val matchedBlocks = NonEmptyList.fromList {
      blockResults.collect { case r@Matched(_, _) => r }
    }
    matchedBlocks match {
      case Some(matchedBlocksNel) =>
        NonEmptyList.fromList {
          findAllMatchedBlocksWithAllowPolicyPrecedingFirstMatchedForbidPolicyBlock(matchedBlocksNel)
        } match {
          case Some(allowedPolicyMatchedBlocks) => Right(allowedPolicyMatchedBlocks)
          case None => Left(())
        }
      case None => Left(())
    }
  }

  private def findAllMatchedBlocksWithAllowPolicyPrecedingFirstMatchedForbidPolicyBlock[B <: BlockContext](blocks: NonEmptyList[Matched[B]]) = {
    blocks.toList.takeWhile { b =>
      b.block.policy match {
        case Policy.Allow => true
        case Policy.Forbid(_) => false
      }
    }
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

  private def nonEmptySetOfMismatchedCausesFromHistory[B <: BlockContext](history: Vector[History[B]]): NonEmptySet[ForbiddenCause] = {
    val causes = rejectionsFrom(history).map {
      case Rejected(None) | Rejected(Some(Rejected.Cause.IndexNotFound | Rejected.Cause.AliasNotFound | Rejected.Cause.TemplateNotFound)) =>
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

  private def wasRejectedDueToIndexNotFound[B <: BlockContext](history: Vector[History[B]]) = {
    val rejections = rejectionsFrom(history)
    !impersonationRejectionExists(rejections) && indexNotFoundRejectionExists(rejections)
  }

  private def wasRejectedDueToAliasNotFound[B <: BlockContext](history: Vector[History[B]]) = {
    val rejections = rejectionsFrom(history)
    !impersonationRejectionExists(rejections) && aliasNotFoundRejectionExists(rejections)
  }

  private def wasRejectedDueToTemplateNotFound[B <: BlockContext](history: Vector[History[B]]) = {
    val rejections = rejectionsFrom(history)
    !impersonationRejectionExists(rejections) && templateNotFoundRejectionExists(rejections)
  }

  private def indexNotFoundRejectionExists(rejections: Vector[Rejected[_]]) = {
    rejections.exists {
      case Rejected(Some(Rejected.Cause.IndexNotFound)) => true
      case Rejected(Some(Rejected.Cause.AliasNotFound)) => false
      case Rejected(Some(Rejected.Cause.TemplateNotFound)) => false
      case Rejected(None) => false
      case Rejected(Some(Rejected.Cause.ImpersonationNotAllowed)) => false
      case Rejected(Some(Rejected.Cause.ImpersonationNotSupported)) => false
    }
  }

  private def aliasNotFoundRejectionExists(rejections: Vector[Rejected[_]]) = {
    rejections.exists {
      case Rejected(Some(Rejected.Cause.AliasNotFound)) => true
      case Rejected(Some(Rejected.Cause.IndexNotFound)) => false
      case Rejected(Some(Rejected.Cause.TemplateNotFound)) => false
      case Rejected(None) => false
      case Rejected(Some(Rejected.Cause.ImpersonationNotAllowed)) => false
      case Rejected(Some(Rejected.Cause.ImpersonationNotSupported)) => false
    }
  }

  private def templateNotFoundRejectionExists(rejections: Vector[Rejected[_]]) = {
    rejections.exists {
      case Rejected(Some(Rejected.Cause.AliasNotFound)) => false
      case Rejected(Some(Rejected.Cause.IndexNotFound)) => false
      case Rejected(Some(Rejected.Cause.TemplateNotFound)) => true
      case Rejected(None) => false
      case Rejected(Some(Rejected.Cause.ImpersonationNotAllowed)) => false
      case Rejected(Some(Rejected.Cause.ImpersonationNotSupported)) => false
    }
  }

  private def impersonationRejectionExists(rejections: Vector[Rejected[_]]) = {
    rejections.exists {
      case Rejected(Some(Rejected.Cause.IndexNotFound)) => false
      case Rejected(Some(Rejected.Cause.AliasNotFound)) => false
      case Rejected(Some(Rejected.Cause.TemplateNotFound)) => false
      case Rejected(None) => false
      case Rejected(Some(Rejected.Cause.ImpersonationNotAllowed)) => true
      case Rejected(Some(Rejected.Cause.ImpersonationNotSupported)) => true
    }
  }

  private def rejectionsFrom[B <: BlockContext](history: Vector[History[B]]): Vector[Rejected[B]] = {
    history.flatMap {
      _.items
        .collect { case h: HistoryItem.RuleHistoryItem[B] => h.result }
        .collect { case r: Rejected[B] => r }
    }
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
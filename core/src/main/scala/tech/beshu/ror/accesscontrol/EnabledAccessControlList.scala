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
import tech.beshu.ror.accesscontrol.AccessControlList.*
import tech.beshu.ror.accesscontrol.EnabledAccessControlList.AccessControlListStaticContext
import tech.beshu.ror.accesscontrol.blocks.Block.BlockExecutionResult.{Matched, Mismatched}
import tech.beshu.ror.accesscontrol.blocks.Block.{BlockExecutionResult, HistoryItem, Policy}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.Result.Rejected
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthenticationRule, AuthorizationRule}
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.FieldsRule
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, BlockContextUpdater, Result}
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.{Group, Header}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.uniquelist.UniqueList

import scala.collection.immutable.ListMap

class EnabledAccessControlList(val blocks: NonEmptyList[Block],
                               override val staticContext: AccessControlListStaticContext)
  extends AccessControlList {

  override val description: String = "Enabled ROR ACL"

  private type AclProcessingResult[B <: BlockContext] = Result[(Block, B)]

  override def handleRegularRequest[B <: BlockContext : BlockContextUpdater](context: RequestContext.Aux[B]): Task[WithHistory[RegularRequestResult[B], B]] = {
    blocks
      .tail
      .foldLeft(checkBlock(blocks.head, context)) { case (currentResult, block) =>
        for {
          prevBlocksExecutionResult <- currentResult
          newCurrentResult <- prevBlocksExecutionResult match {
            case Result.Fulfilled(_) =>
              checkBlock(block, context)
            case Result.Rejected(_) =>
              lift(prevBlocksExecutionResult)
          }
        } yield newCurrentResult
      }
      .run
      .map { case (history, aclProcessingResult) =>
        val res: RegularRequestResult[B] = aclProcessingResult match {
          case Result.Fulfilled((block, blockContext)) =>
            block.policy match {
              case Policy.Allow => RegularRequestResult.Allow(blockContext, block)
              case Policy.Forbid(_) => RegularRequestResult.ForbiddenBy(blockContext, block)
            }
          case Result.Rejected(_) if wasRejectedDueToAliasNotFound(history) =>
            RegularRequestResult.AliasNotFound()
          case Result.Rejected(_) if wasRejectedDueToTemplateNotFound(history) =>
            RegularRequestResult.TemplateNotFound()
          case Result.Rejected(_) =>
            wasRejectedDueToIndexNotFound(history) match {
              case Some(error) =>
                RegularRequestResult.IndexNotFound(error.allowedClusters)
              case None =>
                RegularRequestResult.ForbiddenByMismatched(rejectionCausesPerBlockFrom(history))
            }
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
        val result = matchedAllowedBlocks(blockResults) match {
          case Right(matchedResults) =>
            userMetadataFrom(matchedResults, context.initialBlockContext.userMetadata.currentGroupId) match {
              case Some((userMetadata, matchedBlock)) => UserMetadataRequestResult.Allow(userMetadata, matchedBlock)
              case None => createForbiddenResult(blockResults)
            }
          case Left(()) => createForbiddenResult(blockResults)
        }
        WithHistory(blockResults.toVector, result)
      }
  }

  private def matchedAllowedBlocks[B <: BlockContext](blockResults: Iterable[BlockExecutionResult[B]]) = {
    val matchedBlocks: Option[NonEmptyList[Matched[B]]] = NonEmptyList.fromList {
      blockResults.toList.collect { case r@Matched(_, _, _) => r }
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

  private def userMetadataFrom(matchedResults: NonEmptyList[Matched[CurrentUserMetadataRequestBlockContext]],
                               optPreferredGroupId: Option[GroupId]): Option[(UserMetadata, Block)] = {
    optPreferredGroupId match {
      case Some(preferredGroupId) =>
        val matchingPreferredGroupResults = matchedResults
          .toList
          .flatMap { case result@Matched(_, r, _) =>
            r.context
              .userMetadata.availableGroups
              .find(_.id == preferredGroupId)
              .map(preferredGroup => (result, preferredGroup))
          }
        matchingPreferredGroupResults
          .find { case (matched, preferredGroup) => matched.result.context.userMetadata.kibanaIndex.isDefined }
          .orElse {
            matchingPreferredGroupResults.headOption
          }
          .map { case (matched, preferredGroup) =>
            val userMetadata =
              updateUserMetadataGroups(matched.result.context, Some(preferredGroup), allAvailableGroupsFrom(matchedResults))
            (userMetadata, matched.block)
          }
      case None =>
        Some {
          val Matched(block, result, _) = matchedResults.toList
            .find {
              _.result.context.userMetadata.kibanaIndex.isDefined
            }
            .getOrElse {
              matchedResults.head
            }
          val userMetadata = updateUserMetadataGroups(result.context, None, allAvailableGroupsFrom(matchedResults))
          (userMetadata, block)
        }
    }
  }

  private def createForbiddenResult(blockResults: List[BlockExecutionResult[CurrentUserMetadataRequestBlockContext]]) = {
    val matchedForbidBlock = blockResults.collectFirstSome {
      case m@Matched(block, _, _) => block.policy match {
        case Policy.Allow => None
        case Policy.Forbid(_) => Some(m)
      }
      case Mismatched(_, _, _) => None
    }
    matchedForbidBlock match {
      case Some(Matched(block, r, _)) => UserMetadataRequestResult.ForbiddenBy(r.context, block)
      case None => UserMetadataRequestResult.ForbiddenByMismatched(rejectionCausesPerBlockFrom(blockResults))
    }
  }

  private def allAvailableGroupsFrom(matchedResults: NonEmptyList[Matched[CurrentUserMetadataRequestBlockContext]]) = {
    UniqueList.from(
      matchedResults.toList.flatMap {
        case Matched(_, r, _) => r.context.userMetadata.availableGroups
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

  private def findAllMatchedBlocksWithAllowPolicyPrecedingFirstMatchedForbidPolicyBlock[B <: BlockContext](blocks: NonEmptyList[Matched[B]]) = {
    blocks.toList.takeWhile { b =>
      b.block.policy match {
        case Policy.Allow => true
        case Policy.Forbid(_) => false
      }
    }
  }

  private def checkBlock[B <: BlockContext : BlockContextUpdater](block: Block,
                                                                  requestContext: RequestContext.Aux[B]): WriterT[Task, Vector[BlockExecutionResult[B]], AclProcessingResult[B]] = {
    for {
      blockExecutionResult <- WriterT.liftF(block.execute(requestContext))
      r: Result[(Block, B)] = blockExecutionResult match {
        case Matched(block, f, _) => Result.fulfilled(block -> f.context)
        case Mismatched(_, r, _) => Result.rejected(r.cause)
      }
      aclProcessingResult <- lift(r).tell(Vector(blockExecutionResult))
    } yield aclProcessingResult
  }

  private def lift[B <: BlockContext](result: AclProcessingResult[B]): WriterT[Task, Vector[BlockExecutionResult[B]], AclProcessingResult[B]] = {
    WriterT.value[Task, Vector[BlockExecutionResult[B]], AclProcessingResult[B]](result)
  }

  private def rejectionCausesPerBlockFrom(history: Iterable[BlockExecutionResult[_]]): ListMap[Block.Name, Rejected.Cause] = {
    ListMap.from {
      history.flatMap {
        case Matched(_, _, _) => None
        case Mismatched(block, result, _) => Some(block.name -> result.cause)
      }
    }
  }

  private def wasRejectedDueToIndexNotFound[B <: BlockContext](history: Iterable[BlockExecutionResult[B]]): Option[Rejected.Cause.IndexNotFound] = {
    val rejections = rejectionsFrom(history)
    if (impersonationRejectionExists(rejections)) {
      None
    } else {
      indexNotFoundRejectionExists(rejections)
    }
  }

  private def wasRejectedDueToAliasNotFound[B <: BlockContext](history: Iterable[BlockExecutionResult[B]]) = {
    val rejections = rejectionsFrom(history)
    !impersonationRejectionExists(rejections) && aliasNotFoundRejectionExists(rejections)
  }

  private def wasRejectedDueToTemplateNotFound[B <: BlockContext](history: Iterable[BlockExecutionResult[B]]) = {
    val rejections = rejectionsFrom(history)
    !impersonationRejectionExists(rejections) && templateNotFoundRejectionExists(rejections)
  }

  private def indexNotFoundRejectionExists(rejections: Vector[Rejected[_]]): Option[Rejected.Cause.IndexNotFound] = {
    rejections.collectFirst {
      case Rejected(cause@Rejected.Cause.IndexNotFound(_)) => cause
    }
  }

  private def aliasNotFoundRejectionExists(rejections: Vector[Rejected[_]]) = {
    rejections.exists {
      case Rejected(Rejected.Cause.AliasNotFound) => true
      case Rejected(_: Rejected.Cause.OtherFailure) => false
      case Rejected(_: Rejected.Cause.AuthenticationFailure) => false
      case Rejected(_: Rejected.Cause.AuthorizationFailure) => false
    }
  }

  private def templateNotFoundRejectionExists(rejections: Vector[Rejected[_]]) = {
    rejections.exists {
      case Rejected(Rejected.Cause.TemplateNotFound) => true
      case Rejected(_: Rejected.Cause.OtherFailure) => false
      case Rejected(_: Rejected.Cause.AuthenticationFailure) => false
      case Rejected(_: Rejected.Cause.AuthorizationFailure) => false
    }
  }

  private def impersonationRejectionExists(rejections: Vector[Rejected[_]]) = {
    rejections.exists {
      case Rejected(Rejected.Cause.ImpersonationNotAllowed) => true
      case Rejected(Rejected.Cause.ImpersonationNotSupported) => true
      case Rejected(_: Rejected.Cause.OtherFailure) => false
      case Rejected(_: Rejected.Cause.AuthenticationFailure) => false
      case Rejected(_: Rejected.Cause.AuthorizationFailure) => false
    }
  }

  private def rejectionsFrom[B <: BlockContext](history: Iterable[BlockExecutionResult[B]]): Vector[Rejected[B]] = {
    history
      .flatMap { h =>
        val rulesResultHistory = h match {
          case Matched(_, _, rulesResultHistory) => rulesResultHistory
          case Mismatched(_, _, rulesResultHistory) => rulesResultHistory
        }
        rulesResultHistory
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
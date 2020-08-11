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
package tech.beshu.ror.accesscontrol.acl

import cats.data.{NonEmptyList, NonEmptySet, WriterT}
import cats.implicits._
import monix.eval.Task
import tech.beshu.ror.accesscontrol.AccessControl
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult.ForbiddenByMismatched
import tech.beshu.ror.accesscontrol.AccessControl.{RegularRequestResult, UserMetadataRequestResult, WithHistory}
import tech.beshu.ror.accesscontrol.blocks.Block.ExecutionResult.{Matched, Mismatched}
import tech.beshu.ror.accesscontrol.blocks.Block.{ExecutionResult, History, Policy}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.Group
import tech.beshu.ror.accesscontrol.orders.forbiddenByMismatchedCauseOrder
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.request.RequestContextOps._
import tech.beshu.ror.utils.uniquelist.UniqueList

class AccessControlList(val blocks: NonEmptyList[Block])
  extends AccessControl {

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
              case Policy.Forbid => RegularRequestResult.ForbiddenBy(blockContext, block)
            }
          case Mismatched(_) if wasRejectedDueToIndexNotFound(history) =>
            RegularRequestResult.IndexNotFound()
          case Mismatched(_) if wasRejectedDueToAliasNotFound(history) =>
            RegularRequestResult.AliasNotFound()
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
      .gather(blocks.toList.map(executeBlocksForUserMetadata(_, context)))
      .map(_.flatten)
      .map { blockResults =>
        val history = blockResults.map(_._2).toVector
        val result = matchedAllowedBlocks(blockResults.map(_._1)) match {
          case Right(matchedResults) =>
            userMetadataFrom(matchedResults, context.currentGroup.toOption) match {
              case Some((userMetadata, matchedBlock)) => UserMetadataRequestResult.Allow(userMetadata, matchedBlock)
              case None => UserMetadataRequestResult.Forbidden
            }
          case Left(_) =>
            UserMetadataRequestResult.Forbidden
        }
        WithHistory(history, result)
      }
  }

  private def userMetadataFrom(matchedResults: NonEmptyList[Matched[CurrentUserMetadataRequestBlockContext]],
                               preferredGroup: Option[Group]) = {
    val allGroupsWithRelatedResults =
      matchedResults
        .toList
        .foldLeft(List.empty[(Group, Matched[CurrentUserMetadataRequestBlockContext])]) {
          case (acc, m@Matched(_, blockContext)) =>
            acc ::: blockContext.userMetadata.availableGroups.toList.map((_, m))
          case (acc, _) => acc
        }
    preferredGroup match {
      case Some(pg) =>
        allGroupsWithRelatedResults
          .find { case (group, _) => group == pg }
          .map { case (currentGroup, Matched(block, blockContext)) =>
            val allGroups = UniqueList.fromList(allGroupsWithRelatedResults.map(_._1))
            val userMetadata = createUserMetadata(blockContext, Some(currentGroup), allGroups)
            (userMetadata, block)
          }
      case None =>
        Some {
          allGroupsWithRelatedResults
            .headOption
            .map { case (currentGroup, Matched(block, blockContext)) =>
              val allGroups = UniqueList.fromList(allGroupsWithRelatedResults.map(_._1))
              val userMetadata = createUserMetadata(blockContext, Some(currentGroup), allGroups)
              (userMetadata, block)
            }
            .getOrElse {
              val Matched(block, blockContext) = matchedResults.head
              val userMetadata = createUserMetadata(blockContext, None, UniqueList.empty)
              (userMetadata, block)
            }
        }
    }
  }

  private def createUserMetadata(blockContext: CurrentUserMetadataRequestBlockContext,
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
    NonEmptyList
      .fromList {
        blockResults.collect { case r@Matched(block, _) if block.policy === Policy.Allow => r }
      } match {
      case Some(nel) => Right(nel)
      case None => Left(())
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

  private def nonEmptySetOfMismatchedCausesFromHistory[B <: BlockContext](history: Vector[History[B]]): NonEmptySet[ForbiddenByMismatched.Cause] = {
    val causes = rejectionsFrom(history).map {
      case Rejected(None) | Rejected(Some(Rejected.Cause.IndexNotFound | Rejected.Cause.AliasNotFound)) =>
        ForbiddenByMismatched.Cause.OperationNotAllowed
      case Rejected(Some(Rejected.Cause.ImpersonationNotAllowed)) =>
        ForbiddenByMismatched.Cause.ImpersonationNotAllowed
      case Rejected(Some(Rejected.Cause.ImpersonationNotSupported)) =>
        ForbiddenByMismatched.Cause.ImpersonationNotSupported
    }
    NonEmptyList
      .fromList(causes.toList)
      .getOrElse(NonEmptyList.one(ForbiddenByMismatched.Cause.OperationNotAllowed))
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

  private def indexNotFoundRejectionExists(rejections: Vector[Rejected[_]]) = {
    rejections.exists {
      case Rejected(Some(Rejected.Cause.IndexNotFound)) => true
      case Rejected(Some(Rejected.Cause.AliasNotFound)) => false
      case Rejected(None) => false
      case Rejected(Some(Rejected.Cause.ImpersonationNotAllowed)) => false
      case Rejected(Some(Rejected.Cause.ImpersonationNotSupported)) => false
    }
  }

  private def aliasNotFoundRejectionExists(rejections: Vector[Rejected[_]]) = {
    rejections.exists {
      case Rejected(Some(Rejected.Cause.AliasNotFound)) => true
      case Rejected(Some(Rejected.Cause.IndexNotFound)) => false
      case Rejected(None) => false
      case Rejected(Some(Rejected.Cause.ImpersonationNotAllowed)) => false
      case Rejected(Some(Rejected.Cause.ImpersonationNotSupported)) => false
    }
  }

  private def impersonationRejectionExists(rejections: Vector[Rejected[_]]) = {
    rejections.exists {
      case Rejected(Some(Rejected.Cause.IndexNotFound)) => false
      case Rejected(Some(Rejected.Cause.AliasNotFound)) => false
      case Rejected(None) => false
      case Rejected(Some(Rejected.Cause.ImpersonationNotAllowed)) => true
      case Rejected(Some(Rejected.Cause.ImpersonationNotSupported)) => true
    }
  }

  private def rejectionsFrom[B <: BlockContext](history: Vector[History[B]]): Vector[Rejected[B]] = {
    history.flatMap(_.items.map(_.result).collect { case r: Rejected[B] => r })
  }
}

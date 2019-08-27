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
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, UserMetadata}
import tech.beshu.ror.accesscontrol.domain.Group
import tech.beshu.ror.accesscontrol.orders.{forbiddenByMismatchedCauseOrder, groupOrder}
import tech.beshu.ror.accesscontrol.request.RequestContext

import scala.collection.SortedSet
import tech.beshu.ror.accesscontrol.request.RequestContextOps._

class Acl(val blocks: NonEmptyList[Block])
  extends AccessControl {

  override def handleRegularRequest(context: RequestContext): Task[WithHistory[RegularRequestResult]] = {
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
        val res: RegularRequestResult = result match {
          case Matched(block, blockContext) =>
            block.policy match {
              case Policy.Allow => RegularRequestResult.Allow(blockContext, block)
              case Policy.Forbid => RegularRequestResult.ForbiddenBy(blockContext, block)
            }
          case Mismatched(_) =>
            RegularRequestResult.ForbiddenByMismatched(
              nonEmptySetOfMismatchedCausesFromHistory(history)
            )
        }
        WithHistory(history, res)
      }
      .onErrorHandle { ex =>
        WithHistory(Vector.empty, RegularRequestResult.Failed(ex))
      }
  }

  override def handleMetadataRequest(context: RequestContext): Task[WithHistory[UserMetadataRequestResult]] = {
    Task
      .gather(blocks.toList.map(blockExecute(_, context)))
      .map(_.flatten)
      .map { blockResults =>
        val history = blockResults.map(_._2).toVector
        val result = if(isMatched(blockResults.map(_._1))) {
          val userMetadata = userMetadataFrom(blockResults, context.currentGroup.toOption)
          UserMetadataRequestResult.Allow(userMetadata)
        } else {
          UserMetadataRequestResult.Forbidden
        }
        WithHistory(history, result)
      }
  }

  private def userMetadataFrom(blockResults: List[(ExecutionResult, History)],
                               preferredGroup: Option[Group]) = {
    val matched = blockResults.collect { case r@(Matched(block, _), _) if block.policy === Policy.Allow => r }
    val allGroupsWithRelatedBlockContexts =
      matched
        .map(_._1.blockContext)
        .flatMap(b => b.availableGroups.map((_, b)).toList)
        .sortBy(_._1)
    lazy val defaultMatchedBlockUserMetadata = {
      val blockContext = matched.head._1.blockContext
      createUserMetadata(blockContext, None, SortedSet.empty)
    }
    val foundDesiredGroupBlock = preferredGroup match {
      case Some(pg) => allGroupsWithRelatedBlockContexts.find(_._1 === pg)
      case None => allGroupsWithRelatedBlockContexts.headOption
    }
    foundDesiredGroupBlock
      .map { case (currentGroup, blockContext) =>
        createUserMetadata(blockContext, Some(currentGroup), SortedSet(allGroupsWithRelatedBlockContexts.map(_._1): _*))
      }
      .getOrElse(defaultMatchedBlockUserMetadata)
  }

  private def createUserMetadata(blockContext: BlockContext, currentGroup: Option[Group], availableGroups: SortedSet[Group]) = {
    UserMetadata(
      blockContext.loggedUser,
      currentGroup,
      availableGroups,
      blockContext.kibanaIndex,
      blockContext.hiddenKibanaApps,
      blockContext.kibanaAccess,
      blockContext.userOrigin
    )
  }

  private def blockExecute(block: Block, context: RequestContext) = {
    block
      .execute(context)
      .map(Some.apply)
      .onErrorRecover { case _ => None }
  }

  private def isMatched(blockResults: List[Block.ExecutionResult]): Boolean = {
    blockResults
      .collect { case r@Matched(block, _) if block.policy === Policy.Allow => r}
      .nonEmpty
  }

  private def checkBlock(block: Block, requestContent: RequestContext): WriterT[Task, Vector[History], ExecutionResult] = {
    WriterT.apply {
      block
        .execute(requestContent)
        .map { case (r, history) => (Vector(history), r) }
    }
  }

  private def lift(executionResult: ExecutionResult): WriterT[Task, Vector[History], ExecutionResult] = {
    WriterT.value[Task, Vector[History], ExecutionResult](executionResult)
  }

  private def nonEmptySetOfMismatchedCausesFromHistory(history: Vector[History]): NonEmptySet[ForbiddenByMismatched.Cause] = {
    val rejections = history.flatMap(_.items.map(_.result).collect { case r: Rejected => r })
    val causes = rejections.map {
      case Rejected(None) => ForbiddenByMismatched.Cause.OperationNotAllowed
      case Rejected(Some(Rejected.Cause.ImpersonationNotAllowed)) => ForbiddenByMismatched.Cause.ImpersonationNotAllowed
      case Rejected(Some(Rejected.Cause.ImpersonationNotSupported)) => ForbiddenByMismatched.Cause.ImpersonationNotSupported
    }
    NonEmptyList
      .fromList(causes.toList)
      .getOrElse(NonEmptyList.one(ForbiddenByMismatched.Cause.OperationNotAllowed))
      .toNes
  }
}

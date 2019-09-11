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
import tech.beshu.ror.accesscontrol.logging.LoggingContext
import tech.beshu.ror.accesscontrol.orders.forbiddenByMismatchedCauseOrder
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.request.RequestContextOps._
import tech.beshu.ror.utils.uniquelist.UniqueList

class Acl(val blocks: NonEmptyList[Block], implicit val loggingContext: LoggingContext) // TODO: rename to AccessControlList
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
      .gather(blocks.toList.map(executeBlocksUserMetadataRulesOnly(_, context)))
      .map(_.flatten)
      .map { blockResults =>
        val history = blockResults.map(_._2).toVector
        val result = matchedAllowedBlocks(blockResults.map(_._1)) match {
          case Right(matchedResults) =>
            userMetadataFrom(matchedResults, context.currentGroup.toOption) match {
              case Some(userMetadata) => UserMetadataRequestResult.Allow(userMetadata)
              case None => UserMetadataRequestResult.Forbidden
            }
          case Left(_) =>
            UserMetadataRequestResult.Forbidden
        }
        WithHistory(history, result)
      }
  }

  private def userMetadataFrom(matchedResults: NonEmptyList[Matched],
                               preferredGroup: Option[Group]) = {
    val allGroupsWithRelatedBlockContexts =
      matchedResults
        .toList
        .map(_.blockContext)
        .flatMap(b => b.availableGroups.map((_, b)).toList)
    preferredGroup match {
      case Some(pg) =>
        allGroupsWithRelatedBlockContexts
          .find(_._1 == pg)
          .map { case (currentGroup, blockContext) =>
            createUserMetadata(blockContext, Some(currentGroup), UniqueList.fromList(allGroupsWithRelatedBlockContexts.map(_._1)))
          }
      case None =>
        Some {
          allGroupsWithRelatedBlockContexts
            .headOption
            .map { case (currentGroup, blockContext) =>
              createUserMetadata(blockContext, Some(currentGroup), UniqueList.fromList(allGroupsWithRelatedBlockContexts.map(_._1)))
            }
            .getOrElse {
              createUserMetadata(matchedResults.head.blockContext, None, UniqueList.empty)
            }
        }
    }
  }

  private def createUserMetadata(blockContext: BlockContext, currentGroup: Option[Group], availableGroups: UniqueList[Group]) = {
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

  private def executeBlocksUserMetadataRulesOnly(block: Block, context: RequestContext)
                                                (implicit loggingContext: LoggingContext)= {
    block
      .executeUserMetadataRuleOnly(context)
      .map(Some.apply)
      .onErrorRecover { case _ => None }
  }

  private def matchedAllowedBlocks(blockResults: List[Block.ExecutionResult]) = {
    NonEmptyList
      .fromList {
        blockResults.collect { case r@Matched(block, _) if block.policy === Policy.Allow => r }
      } match {
      case Some(nel) => Right(nel)
      case None => Left(())
    }
  }

  private def checkBlock(block: Block, requestContent: RequestContext)
                        (implicit loggingContext: LoggingContext): WriterT[Task, Vector[History], ExecutionResult] = {
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

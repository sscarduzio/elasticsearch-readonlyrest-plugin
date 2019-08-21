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
import tech.beshu.ror.accesscontrol.AccessControl.{MetadataRequestResult, RegularRequestResult, Result}
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.Block.ExecutionResult.{Matched, Mismatched}
import tech.beshu.ror.accesscontrol.blocks.Block.{ExecutionResult, History, Policy}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected
import tech.beshu.ror.accesscontrol.orders.forbiddenByMismatchedCauseOrder
import tech.beshu.ror.accesscontrol.request.RequestContext

class Acl(val blocks: NonEmptyList[Block])
  extends AccessControl {

  override def handleRegularRequest(context: RequestContext): Task[Result[RegularRequestResult]] = {
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
        Result(history, res)
      }
      .onErrorHandle { ex =>
        Result(Vector.empty, RegularRequestResult.Failed(ex))
      }
  }

  override def handleMetadataRequest(context: RequestContext): Task[Result[MetadataRequestResult]] = ???

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

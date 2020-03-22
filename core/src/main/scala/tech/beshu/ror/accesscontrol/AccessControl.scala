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

import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.accesscontrol.AccessControl.RegularRequestResult.ForbiddenByMismatched.Cause
import tech.beshu.ror.accesscontrol.AccessControl.{RegularRequestResult, UserMetadataRequestResult, WithHistory}
import tech.beshu.ror.accesscontrol.blocks.Block.History
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, CurrentUserMetadataOperationBlockContext}
import tech.beshu.ror.accesscontrol.domain.Operation
import tech.beshu.ror.accesscontrol.domain.Operation.CurrentUserMetadataOperation
import tech.beshu.ror.accesscontrol.request.RequestContext

trait AccessControl {
  def handleRegularRequest[B <: BlockContext.Aux[B, O], O <: Operation](requestContext: RequestContext.Aux[O, B]): Task[WithHistory[RegularRequestResult[B], B]]
  def handleMetadataRequest(requestContext: RequestContext.Aux[CurrentUserMetadataOperation.type, CurrentUserMetadataOperationBlockContext]): Task[WithHistory[UserMetadataRequestResult, CurrentUserMetadataOperationBlockContext]]
}

object AccessControl {

  final case class WithHistory[RESULT, B <: BlockContext[B]](history: Vector[History[B]], result: RESULT)
  object WithHistory {
    def withNoHistory[RESULT, B <: BlockContext[B]](handlingResult: RESULT): WithHistory[RESULT, B] =
      WithHistory(Vector.empty, handlingResult)
  }

  sealed trait RegularRequestResult[B <: BlockContext[B]]
  object RegularRequestResult {
    final case class Allow[B <: BlockContext[B]](blockContext: B, block: Block)
      extends RegularRequestResult[B]
    final case class ForbiddenBy[B <: BlockContext[B]](blockContext: B, block: Block)
      extends RegularRequestResult[B]
    final case class ForbiddenByMismatched[B <: BlockContext[B]](causes: NonEmptySet[Cause])
      extends RegularRequestResult[B]
    case object ForbiddenByMismatched {
      sealed trait Cause
      object Cause {
        case object OperationNotAllowed extends Cause
        case object ImpersonationNotSupported extends Cause
        case object ImpersonationNotAllowed extends Cause
      }
    }
    final case class IndexNotFound[B <: BlockContext[B]]()
      extends RegularRequestResult[B]
    final case class Failed[B <: BlockContext[B]](ex: Throwable)
      extends RegularRequestResult[B]
    final case class PassedThrough[B <: BlockContext[B]]()
      extends RegularRequestResult[B]
  }

  sealed trait UserMetadataRequestResult
  object UserMetadataRequestResult {
    final case class Allow(userMetadata: UserMetadata, block: Block) extends UserMetadataRequestResult
    case object Forbidden extends UserMetadataRequestResult
    case object PassedThrough extends UserMetadataRequestResult
  }
}

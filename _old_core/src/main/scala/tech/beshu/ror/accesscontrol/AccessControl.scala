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
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext}
import tech.beshu.ror.accesscontrol.domain.Operation
import tech.beshu.ror.accesscontrol.request.RequestContext

trait AccessControl {
  def handleRegularRequest[T <: Operation](requestContext: RequestContext[T]): Task[WithHistory[RegularRequestResult[T], T]]
  def handleMetadataRequest[T <: Operation](requestContext: RequestContext[T]): Task[WithHistory[UserMetadataRequestResult, T]]
}

object AccessControl {

  final case class WithHistory[RESULT, T <: Operation](history: Vector[History[T]], result: RESULT)
  object WithHistory {
    def withNoHistory[RESULT, T <: Operation](handlingResult: RESULT): WithHistory[RESULT, T] = WithHistory(Vector.empty, handlingResult)
  }

  sealed trait RegularRequestResult[+T <: Operation]
  object RegularRequestResult {
    final case class Allow[T <: Operation](blockContext: BlockContext[T], block: Block) extends RegularRequestResult[T]
    final case class ForbiddenBy[T <: Operation](blockContext: BlockContext[T], block: Block) extends RegularRequestResult[T]
    final case class ForbiddenByMismatched(causes: NonEmptySet[Cause]) extends RegularRequestResult[Nothing]
    case object ForbiddenByMismatched {
      sealed trait Cause
      object Cause {
        case object OperationNotAllowed extends Cause
        case object ImpersonationNotSupported extends Cause
        case object ImpersonationNotAllowed extends Cause
      }
    }
    case object IndexNotFound extends RegularRequestResult[Nothing]
    final case class Failed(ex: Throwable) extends RegularRequestResult[Nothing]
    case object PassedThrough extends RegularRequestResult[Nothing]
  }

  sealed trait UserMetadataRequestResult
  object UserMetadataRequestResult {
    final case class Allow(userMetadata: UserMetadata, block: Block) extends UserMetadataRequestResult
    case object Forbidden extends UserMetadataRequestResult
    case object PassedThrough extends UserMetadataRequestResult
  }
}

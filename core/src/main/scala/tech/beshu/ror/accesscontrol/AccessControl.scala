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
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, LoggingContext, UserMetadata}
import tech.beshu.ror.accesscontrol.request.RequestContext

trait AccessControl {
  def handleRegularRequest(requestContext: RequestContext)
                          (implicit loggingContext: LoggingContext): Task[WithHistory[RegularRequestResult]]
  def handleMetadataRequest(requestContext: RequestContext)
                           (implicit loggingContext: LoggingContext): Task[WithHistory[UserMetadataRequestResult]]
}

object AccessControl {

  final case class WithHistory[RESULT](history: Vector[History], result: RESULT)
  object WithHistory {
    def withNoHistory[RESULT](handlingResult: RESULT): WithHistory[RESULT] = WithHistory(Vector.empty, handlingResult)
  }

  sealed trait RegularRequestResult
  object RegularRequestResult {
    final case class Allow(blockContext: BlockContext, block: Block) extends RegularRequestResult
    final case class ForbiddenBy(blockContext: BlockContext, block: Block) extends RegularRequestResult
    final case class ForbiddenByMismatched(causes: NonEmptySet[Cause]) extends RegularRequestResult
    case object ForbiddenByMismatched {
      sealed trait Cause
      object Cause {
        case object OperationNotAllowed extends Cause
        case object ImpersonationNotSupported extends Cause
        case object ImpersonationNotAllowed extends Cause
      }
    }
    final case class Failed(ex: Throwable) extends RegularRequestResult
    case object PassedThrough extends RegularRequestResult
  }

  sealed trait UserMetadataRequestResult
  object UserMetadataRequestResult {
    final case class Allow(userMetadata: UserMetadata) extends UserMetadataRequestResult
    case object Forbidden extends UserMetadataRequestResult
    case object PassedThrough extends UserMetadataRequestResult
  }
}

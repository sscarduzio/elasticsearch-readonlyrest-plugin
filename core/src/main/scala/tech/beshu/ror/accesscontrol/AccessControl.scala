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
import tech.beshu.ror.accesscontrol.AccessControl.{MetadataRequestResult, RegularRequestResult, Result}
import tech.beshu.ror.accesscontrol.blocks.Block.History
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext}
import tech.beshu.ror.accesscontrol.domain.{Group, LoggedUser}
import tech.beshu.ror.accesscontrol.request.RequestContext

import scala.collection.SortedSet

trait AccessControl {
  def handleRegularRequest(context: RequestContext): Task[Result[RegularRequestResult]]
  def handleMetadataRequest(context: RequestContext): Task[Result[MetadataRequestResult]]
}

object AccessControl {

  final case class Result[R](history: Vector[History], handlingResult: R)
  object Result {
    def withNoHistory[R](handlingResult: R): Result[R] = Result(Vector.empty, handlingResult)
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

  sealed trait MetadataRequestResult
  object MetadataRequestResult {
    final case class Allow(loggedUser: LoggedUser, availableGroups: SortedSet[Group]) extends MetadataRequestResult
    case object Forbidden extends MetadataRequestResult
    final case class Failed(ex: Throwable) extends MetadataRequestResult
    case object PassedThrough extends MetadataRequestResult
  }
}

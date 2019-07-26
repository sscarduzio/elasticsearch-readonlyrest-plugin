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
package tech.beshu.ror.acl

import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.AclHandlingResult.Result
import tech.beshu.ror.acl.AclHandlingResult.Result.ForbiddenByMismatched.Cause
import tech.beshu.ror.acl.blocks.Block.History
import tech.beshu.ror.acl.blocks.{Block, BlockContext}
import tech.beshu.ror.acl.request.RequestContext

trait Acl {
  def handle(requestContext: RequestContext): Task[AclHandlingResult]
}

trait AclHandlingResult {
  def history: Vector[History]
  def handlingResult: Result
}

object AclHandlingResult {
  sealed trait Result
  object Result {
    final case class Allow(blockContext: BlockContext, block: Block) extends Result
    final case class ForbiddenBy(blockContext: BlockContext, block: Block) extends Result
    final case class ForbiddenByMismatched(causes: NonEmptySet[Cause]) extends Result
    case object ForbiddenByMismatched {
      sealed trait Cause
      object Cause {
        case object ActionNotAllowed extends Cause
        case object ImpersonationNotSupported extends Cause
        case object ImpersonationNotAllowed extends Cause
      }
    }
    final case class Failed(ex: Throwable) extends Result
    case object PassedThrough extends Result
  }
}

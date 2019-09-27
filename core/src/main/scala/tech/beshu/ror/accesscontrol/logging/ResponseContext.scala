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
package tech.beshu.ror.accesscontrol.logging

import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext, UserMetadata}
import tech.beshu.ror.accesscontrol.request.RequestContext

sealed trait ResponseContext {
  def requestContext: RequestContext
}
object ResponseContext {

  final case class AllowedBy(requestContext: RequestContext,
                             block: Block,
                             blockContext: BlockContext,
                             history: Vector[Block.History])
    extends ResponseContext

  final case class Allow(requestContext: RequestContext,
                         userMetadata: UserMetadata,
                         block: Block,
                         history: Vector[Block.History])
    extends ResponseContext

  final case class ForbiddenBy(requestContext: RequestContext,
                               block: Block,
                               blockContext: BlockContext,
                               history: Vector[Block.History])
    extends ResponseContext

  final case class Forbidden(requestContext: RequestContext,
                             history: Vector[Block.History])
    extends ResponseContext

  final case class Errored(requestContext: RequestContext,
                           cause: Throwable)
    extends ResponseContext

}

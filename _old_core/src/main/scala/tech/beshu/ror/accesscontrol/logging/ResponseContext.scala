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

import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext}
import tech.beshu.ror.accesscontrol.domain.Operation
import tech.beshu.ror.accesscontrol.request.RequestContext

sealed trait ResponseContext[+T <: Operation]
object ResponseContext {

  final case class AllowedBy[T <: Operation](requestContext: RequestContext[T],
                                             block: Block,
                                             blockContext: BlockContext[T],
                                             history: Vector[Block.History[T]])
    extends ResponseContext[T]

  final case class Allow[T <: Operation](requestContext: RequestContext[T],
                                         userMetadata: UserMetadata,
                                         block: Block,
                                         history: Vector[Block.History[T]])
    extends ResponseContext[T]

  final case class ForbiddenBy[T <: Operation](requestContext: RequestContext[T],
                                               block: Block,
                                               blockContext: BlockContext[T],
                                               history: Vector[Block.History[T]])
    extends ResponseContext[T]

  final case class Forbidden[T <: Operation](requestContext: RequestContext[T],
                                             history: Vector[Block.History[T]])
    extends ResponseContext[T]

  final case class RequestedIndexNotExist[T <: Operation](requestContext: RequestContext[T],
                                                          history: Vector[Block.History[T]])
    extends ResponseContext[T]

  final case class Errored[T <: Operation](requestContext: RequestContext[T],
                                           cause: Throwable)
    extends ResponseContext[T]

  implicit class RequestContextFromResponseContext[T <: Operation](val response: ResponseContext[T]) extends AnyVal {
    def requestContext: RequestContext[T] = response match {
      case AllowedBy(requestContext, _, _, _) => requestContext
      case Allow(requestContext, _, _, _) => requestContext
      case ForbiddenBy(requestContext, _, _, _) => requestContext
      case Forbidden(requestContext, _) => requestContext
      case RequestedIndexNotExist(requestContext, _) => requestContext
      case Errored(requestContext, _) => requestContext
    }
  }
}

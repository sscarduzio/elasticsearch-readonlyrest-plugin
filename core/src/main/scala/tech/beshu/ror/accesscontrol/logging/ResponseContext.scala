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

sealed trait ResponseContext[B <: BlockContext.Aux[B, O], O <: Operation]
object ResponseContext {

  final case class AllowedBy[B <: BlockContext.Aux[B, O], O <: Operation](requestContext: RequestContext.Aux[O, B],
                                                                          block: Block,
                                                                          blockContext: B,
                                                                          history: Vector[Block.History[B]])
    extends ResponseContext[B, O]

  final case class Allow[B <: BlockContext.Aux[B, O], O <: Operation](requestContext: RequestContext.Aux[O, B],
                                                                      userMetadata: UserMetadata,
                                                                      block: Block,
                                                                      history: Vector[Block.History[B]])
    extends ResponseContext[B, O]

  final case class ForbiddenBy[B <: BlockContext.Aux[B, O], O <: Operation](requestContext: RequestContext.Aux[O, B],
                                                                            block: Block,
                                                                            blockContext: B,
                                                                            history: Vector[Block.History[B]])
    extends ResponseContext[B, O]

  final case class Forbidden[B <: BlockContext.Aux[B, O], O <: Operation](requestContext: RequestContext.Aux[O, B],
                                                                          history: Vector[Block.History[B]])
    extends ResponseContext[B, O]

  final case class RequestedIndexNotExist[B <: BlockContext.Aux[B, O], O <: Operation](requestContext: RequestContext.Aux[O, B],
                                                                                       history: Vector[Block.History[B]])
    extends ResponseContext[B, O]

  final case class Errored[B <: BlockContext.Aux[B, O], O <: Operation](requestContext: RequestContext.Aux[O, B],
                                                                        cause: Throwable)
    extends ResponseContext[B, O]

  implicit class RequestContextFromResponseContext[B <: BlockContext.Aux[B, O], O <: Operation](val response: ResponseContext[B, O]) extends AnyVal {
    def requestContext: RequestContext.Aux[O, B] = response match {
      case AllowedBy(requestContext, _, _, _) => requestContext
      case Allow(requestContext, _, _, _) => requestContext
      case ForbiddenBy(requestContext, _, _, _) => requestContext
      case Forbidden(requestContext, _) => requestContext
      case RequestedIndexNotExist(requestContext, _) => requestContext
      case Errored(requestContext, _) => requestContext
    }
  }
}

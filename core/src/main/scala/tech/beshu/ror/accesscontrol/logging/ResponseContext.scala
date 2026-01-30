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

import tech.beshu.ror.accesscontrol.History
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext}
import tech.beshu.ror.accesscontrol.request.RequestContext

sealed trait ResponseContext[B <: BlockContext] {
  def requestContext: RequestContext.Aux[B]
}

object ResponseContext {

  final case class AllowedBy[B <: BlockContext](requestContext: RequestContext.Aux[B],
                                                block: Block,
                                                blockContext: B,
                                                history: History[B])
    extends ResponseContext[B]

  final case class Allow[B <: BlockContext](requestContext: RequestContext.Aux[B],
                                            userMetadata: UserMetadata,
                                            block: Block,
                                            history: History[B])
    extends ResponseContext[B]

  final case class ForbiddenBy[B <: BlockContext](requestContext: RequestContext.Aux[B],
                                                  block: Block,
                                                  blockContext: B,
                                                  history: History[B])
    extends ResponseContext[B]

  final case class Forbidden[B <: BlockContext](requestContext: RequestContext.Aux[B],
                                                history: History[B])
    extends ResponseContext[B]

  final case class RequestedIndexNotExist[B <: BlockContext](requestContext: RequestContext.Aux[B],
                                                             history: History[B])
    extends ResponseContext[B]

  final case class Errored[B <: BlockContext](requestContext: RequestContext.Aux[B],
                                              cause: Throwable)
    extends ResponseContext[B]
}

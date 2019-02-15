package tech.beshu.ror.acl.logging

import tech.beshu.ror.acl.blocks.{Block, BlockContext}
import tech.beshu.ror.acl.request.RequestContext


sealed trait ResponseContext {
  def requestContext: RequestContext
}
object ResponseContext {

  final case class Allowed(requestContext: RequestContext,
                           block: Block,
                           blockContext: BlockContext,
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

  final case class NotFound(requestContext: RequestContext,
                            cause: Throwable)
    extends ResponseContext

}

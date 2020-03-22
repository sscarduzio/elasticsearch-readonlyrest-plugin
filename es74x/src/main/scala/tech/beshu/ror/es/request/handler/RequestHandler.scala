package tech.beshu.ror.es.request.handler

import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.domain.Operation
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.es.request.context.EsRequest

trait RequestHandler[B <: BlockContext.Aux[B, O], O <: Operation] {

  def handle(request: RequestContext.Aux[O, B] with EsRequest[B]): Task[Unit]
}

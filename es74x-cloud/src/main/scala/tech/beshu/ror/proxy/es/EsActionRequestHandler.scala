/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es

import monix.eval.Task
import monix.execution.Scheduler
import org.elasticsearch.action.delete.DeleteRequest
import org.elasticsearch.action.fieldcaps.FieldCapabilitiesRequest
import org.elasticsearch.action.get.GetRequest
import org.elasticsearch.action.search.{MultiSearchRequest, SearchRequest}
import org.elasticsearch.action.{ActionRequest, ActionResponse}
import org.elasticsearch.common.xcontent.ToXContentObject
import tech.beshu.ror.proxy.es.EsActionRequestHandler.HandlingResult
import tech.beshu.ror.proxy.es.EsActionRequestHandler.HandlingResult.{Handled, PassItThrough}
import tech.beshu.ror.proxy.es.clients.RestHighLevelClientAdapter

class EsActionRequestHandler(esClient: RestHighLevelClientAdapter)
                            (implicit scheduler: Scheduler) {


  def handle(request: ActionRequest): Task[HandlingResult] = {
    tryToHandle
      .andThen(_.map[HandlingResult](Handled))
      .applyOrElse(request, (_: ActionRequest) => Task.now(PassItThrough)) // todo: pass through is not safe here
  }

  // todo: extend it
  private def tryToHandle: PartialFunction[ActionRequest, Task[ActionResponse with ToXContentObject]] = {
    case request: GetRequest => esClient.get(request)
    case request: DeleteRequest => esClient.delete(request)
    case request: SearchRequest => esClient.search(request)
    case request: MultiSearchRequest => esClient.mSearch(request)
    case request: FieldCapabilitiesRequest => esClient.fieldCapabilities(request)
    case other => Task(throw new IllegalStateException(s"not implemented: ${other.getClass.getSimpleName}")) // todo:
  }

}

object EsActionRequestHandler {
  sealed trait HandlingResult
  object HandlingResult {
    final case class Handled(response: ActionResponse with ToXContentObject) extends HandlingResult
    case object PassItThrough extends HandlingResult
  }
}

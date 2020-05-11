/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es

import monix.eval.Task
import monix.execution.Scheduler
import org.elasticsearch.action.bulk.BulkRequest
import org.elasticsearch.action.delete.DeleteRequest
import org.elasticsearch.action.fieldcaps.FieldCapabilitiesRequest
import org.elasticsearch.action.get.{GetRequest, MultiGetRequest}
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.action.search.{MultiSearchRequest, SearchRequest}
import org.elasticsearch.action.{ActionRequest, ActionResponse}
import org.elasticsearch.common.xcontent.ToXContent
import org.elasticsearch.index.reindex.DeleteByQueryRequest
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
  private def tryToHandle: PartialFunction[ActionRequest, Task[ActionResponse with ToXContent]] = {
    case request: IndexRequest => esClient.getIndex(request)
    case request: GetRequest => esClient.get(request)
    case request: MultiGetRequest => esClient.mGet(request)
    case request: DeleteRequest => esClient.delete(request)
    case request: BulkRequest => esClient.bulk(request)
    case request: SearchRequest => esClient.search(request)
    case request: MultiSearchRequest => esClient.mSearch(request)
    case request: FieldCapabilitiesRequest => esClient.fieldCapabilities(request)
    case request: DeleteByQueryRequest => esClient.deleteByQuery(request)
    case other => Task(throw new IllegalStateException(s"not implemented: ${other.getClass.getSimpleName}")) // todo:
  }
}

object EsActionRequestHandler {
  sealed trait HandlingResult
  object HandlingResult {
    final case class Handled(response: ActionResponse with ToXContent) extends HandlingResult
    case object PassItThrough extends HandlingResult
  }
}

/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es

import monix.eval.Task
import monix.execution.Scheduler
import org.elasticsearch.action.admin.cluster.remote.RemoteInfoRequest
import org.elasticsearch.action.admin.indices.template.delete.{DeleteComponentTemplateAction, DeleteComposableIndexTemplateAction}
import org.elasticsearch.action.admin.indices.template.get.{GetComponentTemplateAction, GetComposableIndexTemplateAction}
import org.elasticsearch.action.admin.indices.template.post.SimulateIndexTemplateRequest
import org.elasticsearch.action.admin.indices.template.put.{PutComponentTemplateAction, PutComposableIndexTemplateAction}
import org.elasticsearch.action.bulk.BulkRequest
import org.elasticsearch.action.delete.DeleteRequest
import org.elasticsearch.action.fieldcaps.FieldCapabilitiesRequest
import org.elasticsearch.action.get.{GetRequest, MultiGetRequest}
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.action.main.MainRequest
import org.elasticsearch.action.search.{ClearScrollRequest, MultiSearchRequest, SearchRequest, SearchScrollRequest}
import org.elasticsearch.action.update.UpdateRequest
import org.elasticsearch.action.{ActionRequest, ActionResponse}
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.common.xcontent.ToXContent
import org.elasticsearch.index.reindex.{DeleteByQueryRequest, ReindexRequest, UpdateByQueryRequest}
import org.elasticsearch.script.mustache.{MultiSearchTemplateRequest, SearchTemplateRequest}
import tech.beshu.ror.es.actions.rrauditevent.RRAuditEventRequest
import tech.beshu.ror.proxy.es.EsActionRequestHandler.HandlingResult
import tech.beshu.ror.proxy.es.EsActionRequestHandler.HandlingResult.{Handled, PassItThrough}
import tech.beshu.ror.proxy.es.clients.RestHighLevelClientAdapter
import tech.beshu.ror.proxy.es.proxyaction.ByProxyProcessedRequest

class EsActionRequestHandler(esClient: RestHighLevelClientAdapter,
                             clusterService: ClusterService)
                            (implicit scheduler: Scheduler) {

  def handle(request: ActionRequest): Task[HandlingResult] = {
    tryToHandle
      .andThen(_.map[HandlingResult](Handled))
      .applyOrElse(request, (_: ActionRequest) => Task.now(PassItThrough)) // todo: pass through is not safe here
  }

  private def tryToHandle: PartialFunction[ActionRequest, Task[ActionResponse with ToXContent]] = {
    case request: RRAuditEventRequest => esClient.putRorAuditEvent(request)
    case request: MainRequest => esClient.main(request)
    case request: RemoteInfoRequest => esClient.remoteInfo(request)
    case request: IndexRequest => esClient.getIndex(request)
    case request: GetRequest => esClient.get(request)
    case request: UpdateRequest => esClient.update(request)
    case request: MultiGetRequest => esClient.mGet(request)
    case request: DeleteRequest => esClient.delete(request)
    case request: BulkRequest => esClient.bulk(request)
    case request: SearchRequest => esClient.search(request)
    case request: MultiSearchRequest => esClient.mSearch(request)
    case request: FieldCapabilitiesRequest => esClient.fieldCapabilities(request)
    case request: DeleteByQueryRequest => esClient.deleteByQuery(request)
    case request: UpdateByQueryRequest => esClient.updateByQuery(request)
    case request: SearchTemplateRequest => esClient.searchTemplate(request)
    case request: MultiSearchTemplateRequest => esClient.mSearchTemplate(request)
    case request: ReindexRequest => esClient.reindex(request)
    case request: ByProxyProcessedRequest => esClient.generic(request)
    case request: ClearScrollRequest => esClient.clearScroll(request)
    case request: SearchScrollRequest => esClient.searchScroll(request)
    case request: GetComposableIndexTemplateAction.Request => esClient.getComposableTemplate(request)
    case request: PutComposableIndexTemplateAction.Request => esClient.putComposableTemplate(request)
    case request: DeleteComposableIndexTemplateAction.Request => esClient.deleteComposableTemplate(request)
    case request: GetComponentTemplateAction.Request => esClient.getComponentTemplate(request)
    case request: PutComponentTemplateAction.Request => esClient.putComponentTemplate(request)
    case request: DeleteComponentTemplateAction.Request => esClient.deleteComponentTemplate(request)
    case request: SimulateIndexTemplateRequest => esClient.simulateIndexTemplate(request)
    case other =>
      Task(throw new IllegalStateException(s"not implemented: ${other.getClass.getName}")) // todo:
  }
}

object EsActionRequestHandler {
  sealed trait HandlingResult
  object HandlingResult {
    final case class Handled(response: ActionResponse with ToXContent) extends HandlingResult
    case object PassItThrough extends HandlingResult
  }
}

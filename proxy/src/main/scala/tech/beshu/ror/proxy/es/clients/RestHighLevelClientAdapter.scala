/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients

import monix.eval.Task
import org.apache.http.entity.{ContentType, InputStreamEntity}
import org.elasticsearch.ElasticsearchStatusException
import org.elasticsearch.action.admin.cluster.health.{ClusterHealthRequest, ClusterHealthResponse}
import org.elasticsearch.action.admin.cluster.remote.{RemoteInfoResponse, RemoteInfoRequest => AdminRemoteInfoRequest}
import org.elasticsearch.action.admin.cluster.repositories.cleanup.{CleanupRepositoryRequest, CleanupRepositoryResponse}
import org.elasticsearch.action.admin.cluster.repositories.delete.DeleteRepositoryRequest
import org.elasticsearch.action.admin.cluster.repositories.get.{GetRepositoriesRequest, GetRepositoriesResponse}
import org.elasticsearch.action.admin.cluster.repositories.put.PutRepositoryRequest
import org.elasticsearch.action.admin.cluster.repositories.verify.{VerifyRepositoryRequest, VerifyRepositoryResponse}
import org.elasticsearch.action.admin.cluster.snapshots.create.{CreateSnapshotRequest, CreateSnapshotResponse}
import org.elasticsearch.action.admin.cluster.snapshots.delete.DeleteSnapshotRequest
import org.elasticsearch.action.admin.cluster.snapshots.get.{GetSnapshotsRequest, GetSnapshotsResponse}
import org.elasticsearch.action.admin.cluster.snapshots.restore.{RestoreSnapshotRequest, RestoreSnapshotResponse}
import org.elasticsearch.action.admin.cluster.snapshots.status.{SnapshotsStatusRequest, SnapshotsStatusResponse}
import org.elasticsearch.action.admin.cluster.storedscripts.{DeleteStoredScriptRequest, GetStoredScriptRequest, GetStoredScriptResponse, PutStoredScriptRequest}
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest
import org.elasticsearch.action.admin.indices.alias.exists.AliasesExistResponse
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest
import org.elasticsearch.action.admin.indices.analyze.AnalyzeAction
import org.elasticsearch.action.admin.indices.cache.clear.{ClearIndicesCacheRequest, ClearIndicesCacheResponse}
import org.elasticsearch.action.admin.indices.close.{CloseIndexRequest => AdminCloseIndexRequest, CloseIndexResponse => AdminCloseIndexResponse}
import org.elasticsearch.action.admin.indices.create.CreateIndexRequest
import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequest
import org.elasticsearch.action.admin.indices.exists.indices.{IndicesExistsRequest, IndicesExistsResponse}
import org.elasticsearch.action.admin.indices.flush.{FlushRequest, FlushResponse}
import org.elasticsearch.action.admin.indices.forcemerge.{ForceMergeRequest, ForceMergeResponse}
import org.elasticsearch.action.admin.indices.get.{GetIndexRequest => AdminGetIndexRequest, GetIndexResponse => AdminGetIndexResponse}
import org.elasticsearch.action.admin.indices.mapping.get.{GetFieldMappingsRequest => AdminGetFieldMappingsRequest, GetFieldMappingsResponse => AdminGetFieldMappingsResponse, GetMappingsRequest => AdminGetMappingsRequest, GetMappingsResponse => AdminGetMappingsResponse}
import org.elasticsearch.action.admin.indices.mapping.put.{PutMappingRequest => AdminPutMappingRequest}
import org.elasticsearch.action.admin.indices.open.{OpenIndexRequest, OpenIndexResponse}
import org.elasticsearch.action.admin.indices.refresh.{RefreshRequest, RefreshResponse}
import org.elasticsearch.action.admin.indices.resolve.ResolveIndexAction
import org.elasticsearch.action.admin.indices.rollover.{RolloverRequest, RolloverResponse}
import org.elasticsearch.action.admin.indices.settings.get.{GetSettingsRequest, GetSettingsResponse}
import org.elasticsearch.action.admin.indices.settings.put.UpdateSettingsRequest
import org.elasticsearch.action.admin.indices.shrink.ResizeRequest
import org.elasticsearch.action.admin.indices.stats.{IndicesStatsRequest, IndicesStatsResponse}
import org.elasticsearch.action.admin.indices.template.delete.{DeleteComponentTemplateAction, DeleteComposableIndexTemplateAction, DeleteIndexTemplateRequest}
import org.elasticsearch.action.admin.indices.template.get.{GetComponentTemplateAction, GetComposableIndexTemplateAction, GetIndexTemplatesRequest => AdminGetIndexTemplatesRequest, GetIndexTemplatesResponse => AdminGetIndexTemplatesResponse}
import org.elasticsearch.action.admin.indices.template.post.{SimulateIndexTemplateRequest => AdminSimulateIndexTemplateRequest, SimulateIndexTemplateResponse => AdminSimulateIndexTemplateResponse}
import org.elasticsearch.action.admin.indices.template.put.{PutComponentTemplateAction, PutComposableIndexTemplateAction, PutIndexTemplateRequest}
import org.elasticsearch.action.admin.indices.validate.query.{ValidateQueryRequest, ValidateQueryResponse}
import org.elasticsearch.action.admin.indices.{create, shrink}
import org.elasticsearch.action.bulk.{BulkRequest, BulkResponse}
import org.elasticsearch.action.delete.{DeleteRequest, DeleteResponse}
import org.elasticsearch.action.fieldcaps.{FieldCapabilitiesRequest, FieldCapabilitiesResponse}
import org.elasticsearch.action.get._
import org.elasticsearch.action.index.{IndexRequest, IndexResponse}
import org.elasticsearch.action.main.{MainRequest, MainResponse}
import org.elasticsearch.action.search._
import org.elasticsearch.action.support.master.AcknowledgedResponse
import org.elasticsearch.action.update.{UpdateRequest, UpdateResponse}
import org.elasticsearch.client._
import org.elasticsearch.client.cluster.RemoteInfoRequest
import org.elasticsearch.client.core.CountRequest
import org.elasticsearch.client.indices._
import org.elasticsearch.index.reindex.{BulkByScrollResponse, DeleteByQueryRequest, ReindexRequest, UpdateByQueryRequest}
import org.elasticsearch.rest.RestRequest
import org.elasticsearch.script.mustache.{MultiSearchTemplateRequest, MultiSearchTemplateResponse, SearchTemplateRequest, SearchTemplateResponse}
import tech.beshu.ror.accesscontrol.domain.FullLocalIndexWithAliases
import tech.beshu.ror.es.actions.rrauditevent.{RRAuditEventRequest, RRAuditEventResponse}
import tech.beshu.ror.es.utils.GenericResponseListener
import tech.beshu.ror.proxy.es.clients.RestHighLevelClientAdapter._
import tech.beshu.ror.proxy.es.clients.actions.AllIndicesAndAliases
import tech.beshu.ror.proxy.es.clients.actions.ResolveIndex._
import tech.beshu.ror.proxy.es.clients.actions.utils.ElasticsearchStatusExceptionOps._
import tech.beshu.ror.proxy.es.exceptions._
import tech.beshu.ror.proxy.es.proxyaction.{ByProxyProcessedRequest, ByProxyProcessedResponse}

import scala.collection.JavaConverters._

// todo: neat response handling when ES is not available (client throws connection error or times out)
// todo: use client async api
class RestHighLevelClientAdapter(client: RestHighLevelClient) {

  def putRorAuditEvent(request: RRAuditEventRequest): Task[RRAuditEventResponse] =
    Task.now(new RRAuditEventResponse())

  def generic(request: ByProxyProcessedRequest): Task[ByProxyProcessedResponse] = {
    executeAsync {
      client
        .getLowLevelClient
        .performRequest(clientRequestFrom(request.rest))
    } map { response =>
      new ByProxyProcessedResponse(response)
    }
  }

  def main(request: MainRequest): Task[MainResponse] = {
    import tech.beshu.ror.proxy.es.clients.actions.Info._
    executeAsync(client.info(RequestOptions.DEFAULT))
      .map(_.toMainResponse)
  }

  def getIndex(request: IndexRequest): Task[IndexResponse] = {
    executeAsync(client.index(request, RequestOptions.DEFAULT))
  }

  def createIndex(request: CreateIndexRequest): Task[create.CreateIndexResponse] = {
    executeAsync(client.indices().create(request, RequestOptions.DEFAULT))
  }

  def deleteIndex(request: DeleteIndexRequest): Task[AcknowledgedResponse] = {
    executeAsync(client.indices().delete(request, RequestOptions.DEFAULT))
  }

  def closeIndex(request: AdminCloseIndexRequest): Task[AdminCloseIndexResponse] = {
    import tech.beshu.ror.proxy.es.clients.actions.CloseIndex._
    executeAsync(client.indices().close(request.toCloseIndexRequest, RequestOptions.DEFAULT))
      .map(_.toCloseAdminResponse)
  }

  def openIndex(request: OpenIndexRequest): Task[OpenIndexResponse] = {
    executeAsync(client.indices().open(request, RequestOptions.DEFAULT))
  }

  def refresh(request: RefreshRequest): Task[RefreshResponse] = {
    executeAsync(client.indices().refresh(request, RequestOptions.DEFAULT))
  }

  def flush(request: FlushRequest): Task[FlushResponse] = {
    executeAsync(client.indices().flush(request, RequestOptions.DEFAULT))
  }

  def forceMerge(request: ForceMergeRequest): Task[ForceMergeResponse] = {
    executeAsync(client.indices().forcemerge(request, RequestOptions.DEFAULT))
  }

  def indicesExists(request: IndicesExistsRequest): Task[IndicesExistsResponse] = {
    executeAsync(client.indices().exists(new GetIndexRequest(request.indices(): _*), RequestOptions.DEFAULT))
      .map(response => new IndicesExistsResponse(response))
  }

  def getMappings(request: AdminGetMappingsRequest): Task[AdminGetMappingsResponse] = {
    import tech.beshu.ror.proxy.es.clients.actions.GetMappings._
    executeAsync(client.indices().getMapping(request.toGetMappingsRequest, RequestOptions.DEFAULT))
      .map(_.toGetMappingsResponse)
  }

  def getFieldMappings(request: AdminGetFieldMappingsRequest): Task[AdminGetFieldMappingsResponse] = {
    import tech.beshu.ror.proxy.es.clients.actions.GetFieldMappings._
    executeAsync(client.indices().getFieldMapping(request.toGetFieldMappingsRequest, RequestOptions.DEFAULT))
      .map(_.toGetFieldMappingsResponse)
  }

  def putMappings(request: AdminPutMappingRequest): Task[AcknowledgedResponse] = {
    executeAsync(client.indices().putMapping(request, RequestOptions.DEFAULT))
  }

  def search(request: SearchRequest): Task[SearchResponse] = {
    val listener = new GenericResponseListener[SearchResponse]
    client.searchAsync(request, RequestOptions.DEFAULT, listener)
    listener.result
      .recoverWithSpecializedException
  }

  def mSearch(request: MultiSearchRequest): Task[MultiSearchResponse] = {
    import tech.beshu.ror.proxy.es.clients.actions.MSearch._

    val listener = new GenericResponseListener[MultiSearchResponse]
    client.msearchAsync(request, RequestOptions.DEFAULT, listener)
    listener.result
      .map(_.toResponseWithSpecializedException)
      .recoverWithSpecializedException
  }

  def health(request: ClusterHealthRequest): Task[ClusterHealthResponse] = {
    executeAsync(client.cluster.health(request, RequestOptions.DEFAULT))
  }

  def getSettings(request: GetSettingsRequest): Task[GetSettingsResponse] = {
    executeAsync(client.indices().getSettings(request, RequestOptions.DEFAULT))
  }

  def getAlias(request: GetAliasesRequest): Task[GetAliasesResponse] = {
    import tech.beshu.ror.proxy.es.clients.actions.GetAliases._
    executeAsync(client.indices().getAlias(request, RequestOptions.DEFAULT))
      .map(_.toResponseWithSpecializedException)
  }

  def getAllIndicesAndAliases: Task[Set[FullLocalIndexWithAliases]] = {
    import tech.beshu.ror.proxy.es.clients.actions.AllIndicesAndAliases._
    executeAsync(
      client
        .getLowLevelClient
        .performRequest(AllIndicesAndAliases.request)
        .toResponse
        .get
    )
  }

  def aliasesExist(request: GetAliasesRequest): Task[AliasesExistResponse] = {
    executeAsync(client.indices().existsAlias(request, RequestOptions.DEFAULT))
      .map(new AliasesExistResponse(_))
  }

  def updateAliases(request: IndicesAliasesRequest): Task[AcknowledgedResponse] = {
    executeAsync(client.indices().updateAliases(request, RequestOptions.DEFAULT))
  }

  def get(request: GetRequest): Task[GetResponse] = {
    executeAsync(client.get(request, RequestOptions.DEFAULT))
  }

  def mGet(request: MultiGetRequest): Task[MultiGetResponse] = {
    import tech.beshu.ror.proxy.es.clients.actions.MultiGet._
    executeAsync(client.mget(request, RequestOptions.DEFAULT))
      .map(_.toResponseWithSpecializedException)
  }

  def update(request: UpdateRequest): Task[UpdateResponse] = {
    executeAsync(client.update(request, RequestOptions.DEFAULT))
  }

  def delete(request: DeleteRequest): Task[DeleteResponse] = {
    executeAsync(client.delete(request, RequestOptions.DEFAULT))
  }

  def bulk(request: BulkRequest): Task[BulkResponse] = {
    executeAsync(client.bulk(request, RequestOptions.DEFAULT))
  }

  def getIndex(request: AdminGetIndexRequest): Task[AdminGetIndexResponse] = {
    executeAsync(client.indices().get(request, RequestOptions.DEFAULT))
  }

  def getIndex(request: GetIndexRequest): Task[GetIndexResponse] = {
    executeAsync(client.indices().get(request, RequestOptions.DEFAULT))
  }

  def clearCache(request: ClearIndicesCacheRequest): Task[ClearIndicesCacheResponse] = {
    executeAsync(client.indices().clearCache(request, RequestOptions.DEFAULT))
  }

  def getTemplate(request: AdminGetIndexTemplatesRequest): Task[AdminGetIndexTemplatesResponse] = {
    import tech.beshu.ror.proxy.es.clients.actions.GetTemplate._
    executeAsync(client.indices().getIndexTemplate(request.toGetTemplateRequest, RequestOptions.DEFAULT))
      .map(_.toGetTemplateResponse)
      .onErrorRecover { case ex: ElasticsearchStatusException if ex.isNotFound =>
        new AdminGetIndexTemplatesResponse(List.empty[org.elasticsearch.cluster.metadata.IndexTemplateMetadata].asJava)
      }
  }

  def putTemplate(request: PutIndexTemplateRequest): Task[AcknowledgedResponse] = {
    executeAsync(client.indices().putTemplate(request, RequestOptions.DEFAULT))
  }

  def deleteTemplate(request: DeleteIndexTemplateRequest): Task[AcknowledgedResponse] = {
    executeAsync(client.indices().deleteTemplate(request, RequestOptions.DEFAULT))
  }

  def simulateIndexTemplate(request: AdminSimulateIndexTemplateRequest): Task[AdminSimulateIndexTemplateResponse] = {
    import tech.beshu.ror.proxy.es.clients.actions.SimulateIndexTemplate._
    executeAsync(client.indices().simulateIndexTemplate(request.toSimulateIndexTemplateRequest, RequestOptions.DEFAULT))
      .map(_.toSimulateIndexTemplateResponse)
  }

  def getComposableTemplate(request: GetComposableIndexTemplateAction.Request): Task[GetComposableIndexTemplateAction.Response] = {
    import tech.beshu.ror.proxy.es.clients.actions.GetComposableTemplate._
    executeAsync(client.indices().getIndexTemplate(request.toGetComposableTemplateRequest, RequestOptions.DEFAULT))
      .map(_.toGetComposableTemplateResponse)
      .onErrorRecover(notFoundResponseOf(request))
  }

  def putComposableTemplate(request: PutComposableIndexTemplateAction.Request): Task[AcknowledgedResponse] = {
    import tech.beshu.ror.proxy.es.clients.actions.PutComposableTemplate._
    executeAsync(client.indices().putIndexTemplate(request.toPutComposableTemplateRequest, RequestOptions.DEFAULT))
  }

  def deleteComposableTemplate(request: DeleteComposableIndexTemplateAction.Request): Task[AcknowledgedResponse] = {
    import tech.beshu.ror.proxy.es.clients.actions.DeleteComposableTemplate._
    executeAsync(client.indices().deleteIndexTemplate(request.toDeleteComposableTemplateRequest, RequestOptions.DEFAULT))
  }

  def getComponentTemplate(request: GetComponentTemplateAction.Request): Task[GetComponentTemplateAction.Response] = {
    import tech.beshu.ror.proxy.es.clients.actions.GetComponentTemplate._
    executeAsync(client.cluster().getComponentTemplate(request.toGetComponentTemplateRequest, RequestOptions.DEFAULT))
      .map(_.toGetComponentTemplateResponse)
      .onErrorRecover(notFoundResponseOf(request))
  }

  def putComponentTemplate(request: PutComponentTemplateAction.Request): Task[AcknowledgedResponse] = {
    import tech.beshu.ror.proxy.es.clients.actions.PutComponentTemplate._
    executeAsync(client.cluster().putComponentTemplate(request.toPutComponentTemplateRequest, RequestOptions.DEFAULT))
  }

  def deleteComponentTemplate(request: DeleteComponentTemplateAction.Request): Task[AcknowledgedResponse] = {
    import tech.beshu.ror.proxy.es.clients.actions.DeleteComponentTemplate._
    executeAsync(client.cluster().deleteComponentTemplate(request.toDeleteComponentTemplateRequest, RequestOptions.DEFAULT))
  }

  def stats(request: IndicesStatsRequest): Task[IndicesStatsResponse] = {
    import tech.beshu.ror.proxy.es.clients.actions.IndicesStats._
    executeAsync(client.count(new CountRequest(), RequestOptions.DEFAULT))
      .map(_.toIndicesStatsResponse)
  }

  def fieldCapabilities(request: FieldCapabilitiesRequest): Task[FieldCapabilitiesResponse] = {
    executeAsync(client.fieldCaps(request, RequestOptions.DEFAULT))
  }

  def deleteByQuery(request: DeleteByQueryRequest): Task[BulkByScrollResponse] = {
    executeAsync(client.deleteByQuery(request, RequestOptions.DEFAULT))
  }

  def updateByQuery(request: UpdateByQueryRequest): Task[BulkByScrollResponse] = {
    executeAsync(client.updateByQuery(request, RequestOptions.DEFAULT))
  }

  def putScript(request: PutStoredScriptRequest): Task[AcknowledgedResponse] = {
    executeAsync(client.putScript(request, RequestOptions.DEFAULT))
  }

  def getScript(request: GetStoredScriptRequest): Task[GetStoredScriptResponse] = {
    executeAsync(client.getScript(request, RequestOptions.DEFAULT))
  }

  def deleteScript(request: DeleteStoredScriptRequest): Task[AcknowledgedResponse] = {
    executeAsync(client.deleteScript(request, RequestOptions.DEFAULT))
  }

  def searchTemplate(request: SearchTemplateRequest): Task[SearchTemplateResponse] = {
    executeAsync(client.searchTemplate(request, RequestOptions.DEFAULT))
  }

  def mSearchTemplate(request: MultiSearchTemplateRequest): Task[MultiSearchTemplateResponse] = {
    executeAsync(client.msearchTemplate(request, RequestOptions.DEFAULT))
  }

  def reindex(request: ReindexRequest): Task[BulkByScrollResponse] = {
    executeAsync(client.reindex(request, RequestOptions.DEFAULT))
  }

  def updateSettings(request: UpdateSettingsRequest): Task[AcknowledgedResponse] = {
    executeAsync(client.indices().putSettings(request, RequestOptions.DEFAULT))
  }

  def analyze(request: AnalyzeAction.Request): Task[AnalyzeAction.Response] = {
    import tech.beshu.ror.proxy.es.clients.actions.Analyze._
    executeAsync(client.indices().analyze(request.toAnalyzeRequest, RequestOptions.DEFAULT))
      .map(_.toAnalyzeResponse)
  }

  def validateQuery(request: ValidateQueryRequest): Task[ValidateQueryResponse] = {
    executeAsync(client.indices().validateQuery(request, RequestOptions.DEFAULT))
  }

  def resizeIndex(request: ResizeRequest): Task[shrink.ResizeResponse] = {
    executeAsync(client.indices().shrink(request, RequestOptions.DEFAULT))
  }

  def rolloverIndex(request: RolloverRequest): Task[RolloverResponse] = {
    executeAsync(client.indices().rollover(request, RequestOptions.DEFAULT))
  }

  def resolveIndex(request: ResolveIndexAction.Request): Task[ResolveIndexAction.Response] = {
    executeAsync(
      client
        .getLowLevelClient
        .performRequest(request.toLowLevel)
        .toResponse
        .get
    )
  }

  def getSnapshots(request: GetSnapshotsRequest): Task[GetSnapshotsResponse] = {
    executeAsync(client.snapshot().get(request, RequestOptions.DEFAULT))
  }

  def createSnapshot(request: CreateSnapshotRequest): Task[CreateSnapshotResponse] = {
    executeAsync(client.snapshot().create(request, RequestOptions.DEFAULT))
  }

  def deleteSnapshot(request: DeleteSnapshotRequest): Task[AcknowledgedResponse] = {
    executeAsync(client.snapshot().delete(request, RequestOptions.DEFAULT))
  }

  def restoreSnapshot(request: RestoreSnapshotRequest): Task[RestoreSnapshotResponse] = {
    executeAsync(client.snapshot().restore(request, RequestOptions.DEFAULT))
  }

  def snapshotsStatus(request: SnapshotsStatusRequest): Task[SnapshotsStatusResponse] = {
    executeAsync(client.snapshot().status(request, RequestOptions.DEFAULT))
  }

  def getRepositories(request: GetRepositoriesRequest): Task[GetRepositoriesResponse] = {
    executeAsync(client.snapshot().getRepository(request, RequestOptions.DEFAULT))
  }

  def putRepository(request: PutRepositoryRequest): Task[AcknowledgedResponse] = {
    executeAsync(client.snapshot().createRepository(request, RequestOptions.DEFAULT))
  }

  def deleteRepository(request: DeleteRepositoryRequest): Task[AcknowledgedResponse] = {
    executeAsync(client.snapshot().deleteRepository(request, RequestOptions.DEFAULT))
  }

  def verifyRepository(request: VerifyRepositoryRequest): Task[VerifyRepositoryResponse] = {
    executeAsync(client.snapshot().verifyRepository(request, RequestOptions.DEFAULT))
  }

  def cleanupRepository(request: CleanupRepositoryRequest): Task[CleanupRepositoryResponse] = {
    executeAsync(client.snapshot().cleanupRepository(request, RequestOptions.DEFAULT))
  }

  def remoteInfo(request: AdminRemoteInfoRequest): Task[RemoteInfoResponse] = {
    import tech.beshu.ror.proxy.es.clients.actions.RemoteInfo._
    executeAsync(client.cluster().remoteInfo(new RemoteInfoRequest(), RequestOptions.DEFAULT))
      .map(_.toRemoteInfoResponse)
  }

  def clearScroll(request: ClearScrollRequest): Task[ClearScrollResponse] = {
    executeAsync(client.clearScroll(request, RequestOptions.DEFAULT))
  }

  def searchScroll(request: SearchScrollRequest): Task[SearchResponse] = {
    executeAsync(client.scroll(request, RequestOptions.DEFAULT))
  }

  def close(): Unit = {
    client.close()
  }

  private def executeAsync[T](action: => T): Task[T] =
    Task(action).recoverWithSpecializedException

  private def clientRequestFrom(restRequest: RestRequest) = {
    val clientRequest = new Request(restRequest.method().toString, restRequest.path())
    restRequest
      .params().asScala
      .foreach { case (name, value) =>
        clientRequest.addParameter(name, value)
      }
    val entity = Option(restRequest.header("Content-Type")) match {
      case Some(contentType) =>
        new InputStreamEntity(restRequest.content().streamInput(), ContentType.parse(contentType))
      case None =>
        new InputStreamEntity(restRequest.content().streamInput())
    }
    clientRequest.setEntity(entity)
    clientRequest
  }
}

object RestHighLevelClientAdapter {

  implicit class TaskOps[A](val task: Task[A]) extends AnyVal {

    def recoverWithSpecializedException: Task[A] =
      task.onErrorRecover {
        case ex: ElasticsearchStatusException =>
          throw ex.toSpecializedException
        case ex: ResponseException =>
          throw ex.toSpecializedException
      }
  }
}
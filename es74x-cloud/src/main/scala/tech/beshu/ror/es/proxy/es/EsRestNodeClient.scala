package tech.beshu.ror.es.proxy.es

import java.util
import java.util.function.Supplier

import monix.execution.Scheduler
import org.apache.http.HttpHost
import org.elasticsearch.action._
import org.elasticsearch.action.admin.cluster.allocation.{ClusterAllocationExplainRequest, ClusterAllocationExplainRequestBuilder, ClusterAllocationExplainResponse}
import org.elasticsearch.action.admin.cluster.health.{ClusterHealthRequest, ClusterHealthRequestBuilder, ClusterHealthResponse}
import org.elasticsearch.action.admin.cluster.node.hotthreads.{NodesHotThreadsRequest, NodesHotThreadsRequestBuilder, NodesHotThreadsResponse}
import org.elasticsearch.action.admin.cluster.node.info.{NodesInfoRequest, NodesInfoRequestBuilder, NodesInfoResponse}
import org.elasticsearch.action.admin.cluster.node.reload.NodesReloadSecureSettingsRequestBuilder
import org.elasticsearch.action.admin.cluster.node.stats.{NodesStatsRequest, NodesStatsRequestBuilder, NodesStatsResponse}
import org.elasticsearch.action.admin.cluster.node.tasks.cancel.{CancelTasksRequest, CancelTasksRequestBuilder, CancelTasksResponse}
import org.elasticsearch.action.admin.cluster.node.tasks.get.{GetTaskRequest, GetTaskRequestBuilder, GetTaskResponse}
import org.elasticsearch.action.admin.cluster.node.tasks.list.{ListTasksRequest, ListTasksRequestBuilder, ListTasksResponse}
import org.elasticsearch.action.admin.cluster.node.usage.{NodesUsageRequest, NodesUsageRequestBuilder, NodesUsageResponse}
import org.elasticsearch.action.admin.cluster.repositories.cleanup.{CleanupRepositoryRequest, CleanupRepositoryRequestBuilder, CleanupRepositoryResponse}
import org.elasticsearch.action.admin.cluster.repositories.delete.{DeleteRepositoryRequest, DeleteRepositoryRequestBuilder}
import org.elasticsearch.action.admin.cluster.repositories.get.{GetRepositoriesRequest, GetRepositoriesRequestBuilder, GetRepositoriesResponse}
import org.elasticsearch.action.admin.cluster.repositories.put.{PutRepositoryRequest, PutRepositoryRequestBuilder}
import org.elasticsearch.action.admin.cluster.repositories.verify.{VerifyRepositoryRequest, VerifyRepositoryRequestBuilder, VerifyRepositoryResponse}
import org.elasticsearch.action.admin.cluster.reroute.{ClusterRerouteRequest, ClusterRerouteRequestBuilder, ClusterRerouteResponse}
import org.elasticsearch.action.admin.cluster.settings.{ClusterUpdateSettingsRequest, ClusterUpdateSettingsRequestBuilder, ClusterUpdateSettingsResponse}
import org.elasticsearch.action.admin.cluster.shards.{ClusterSearchShardsRequest, ClusterSearchShardsRequestBuilder, ClusterSearchShardsResponse}
import org.elasticsearch.action.admin.cluster.snapshots.create.{CreateSnapshotRequest, CreateSnapshotRequestBuilder, CreateSnapshotResponse}
import org.elasticsearch.action.admin.cluster.snapshots.delete.{DeleteSnapshotRequest, DeleteSnapshotRequestBuilder}
import org.elasticsearch.action.admin.cluster.snapshots.get.{GetSnapshotsRequest, GetSnapshotsRequestBuilder, GetSnapshotsResponse}
import org.elasticsearch.action.admin.cluster.snapshots.restore.{RestoreSnapshotRequest, RestoreSnapshotRequestBuilder, RestoreSnapshotResponse}
import org.elasticsearch.action.admin.cluster.snapshots.status.{SnapshotsStatusRequest, SnapshotsStatusRequestBuilder, SnapshotsStatusResponse}
import org.elasticsearch.action.admin.cluster.state.{ClusterStateRequest, ClusterStateRequestBuilder, ClusterStateResponse}
import org.elasticsearch.action.admin.cluster.stats.{ClusterStatsRequest, ClusterStatsRequestBuilder, ClusterStatsResponse}
import org.elasticsearch.action.admin.cluster.storedscripts._
import org.elasticsearch.action.admin.cluster.tasks.{PendingClusterTasksRequest, PendingClusterTasksRequestBuilder, PendingClusterTasksResponse}
import org.elasticsearch.action.admin.indices.alias.exists.{AliasesExistRequestBuilder, AliasesExistResponse}
import org.elasticsearch.action.admin.indices.alias.get.{GetAliasesRequest, GetAliasesRequestBuilder, GetAliasesResponse}
import org.elasticsearch.action.admin.indices.alias.{IndicesAliasesRequest, IndicesAliasesRequestBuilder}
import org.elasticsearch.action.admin.indices.analyze.{AnalyzeAction, AnalyzeRequestBuilder}
import org.elasticsearch.action.admin.indices.cache.clear.{ClearIndicesCacheRequest, ClearIndicesCacheRequestBuilder, ClearIndicesCacheResponse}
import org.elasticsearch.action.admin.indices.close.{CloseIndexRequest, CloseIndexRequestBuilder, CloseIndexResponse}
import org.elasticsearch.action.admin.indices.create.{CreateIndexRequest, CreateIndexRequestBuilder, CreateIndexResponse}
import org.elasticsearch.action.admin.indices.delete.{DeleteIndexRequest, DeleteIndexRequestBuilder}
import org.elasticsearch.action.admin.indices.exists.indices.{IndicesExistsRequest, IndicesExistsRequestBuilder, IndicesExistsResponse}
import org.elasticsearch.action.admin.indices.exists.types.{TypesExistsRequest, TypesExistsRequestBuilder, TypesExistsResponse}
import org.elasticsearch.action.admin.indices.flush._
import org.elasticsearch.action.admin.indices.forcemerge.{ForceMergeRequest, ForceMergeRequestBuilder, ForceMergeResponse}
import org.elasticsearch.action.admin.indices.get.{GetIndexRequest, GetIndexRequestBuilder, GetIndexResponse}
import org.elasticsearch.action.admin.indices.mapping.get._
import org.elasticsearch.action.admin.indices.mapping.put.{PutMappingRequest, PutMappingRequestBuilder}
import org.elasticsearch.action.admin.indices.open.{OpenIndexRequest, OpenIndexRequestBuilder, OpenIndexResponse}
import org.elasticsearch.action.admin.indices.recovery.{RecoveryRequest, RecoveryRequestBuilder, RecoveryResponse}
import org.elasticsearch.action.admin.indices.refresh.{RefreshRequest, RefreshRequestBuilder, RefreshResponse}
import org.elasticsearch.action.admin.indices.rollover.{RolloverRequest, RolloverRequestBuilder, RolloverResponse}
import org.elasticsearch.action.admin.indices.segments.{IndicesSegmentResponse, IndicesSegmentsRequest, IndicesSegmentsRequestBuilder}
import org.elasticsearch.action.admin.indices.settings.get.{GetSettingsRequest, GetSettingsRequestBuilder, GetSettingsResponse}
import org.elasticsearch.action.admin.indices.settings.put.{UpdateSettingsRequest, UpdateSettingsRequestBuilder}
import org.elasticsearch.action.admin.indices.shards.{IndicesShardStoreRequestBuilder, IndicesShardStoresRequest, IndicesShardStoresResponse}
import org.elasticsearch.action.admin.indices.shrink.{ResizeRequest, ResizeRequestBuilder, ResizeResponse}
import org.elasticsearch.action.admin.indices.stats.{IndicesStatsRequest, IndicesStatsRequestBuilder, IndicesStatsResponse}
import org.elasticsearch.action.admin.indices.template.delete.{DeleteIndexTemplateRequest, DeleteIndexTemplateRequestBuilder}
import org.elasticsearch.action.admin.indices.template.get.{GetIndexTemplatesRequest, GetIndexTemplatesRequestBuilder, GetIndexTemplatesResponse}
import org.elasticsearch.action.admin.indices.template.put.{PutIndexTemplateRequest, PutIndexTemplateRequestBuilder}
import org.elasticsearch.action.admin.indices.upgrade.get.{UpgradeStatusRequest, UpgradeStatusRequestBuilder, UpgradeStatusResponse}
import org.elasticsearch.action.admin.indices.upgrade.post.{UpgradeRequest, UpgradeRequestBuilder, UpgradeResponse}
import org.elasticsearch.action.admin.indices.validate.query.{ValidateQueryRequest, ValidateQueryRequestBuilder, ValidateQueryResponse}
import org.elasticsearch.action.ingest._
import org.elasticsearch.action.support.TransportAction
import org.elasticsearch.action.support.master.AcknowledgedResponse
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.client.{SyncedFlushResponse => _, _}
import org.elasticsearch.cluster.{ClusterName, ClusterState}
import org.elasticsearch.common.bytes.BytesReference
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.common.xcontent.XContentType
import org.elasticsearch.tasks.{Task, TaskId, TaskListener}
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.transport.RemoteClusterService
import org.joor.Reflect._

class EsRestNodeClient(underlying: NodeClient,
                       esClient: RestHighLevelClientAdapter,
                       settings: Settings,
                       threadPool: ThreadPool)
                      (implicit scheduler: Scheduler)
  extends NodeClient(settings, threadPool) {

  private val customAdminClient = new AdminClient {
    override def cluster(): ClusterAdminClient = new ClusterAdminClient {
      override def health(request: ClusterHealthRequest): ActionFuture[ClusterHealthResponse] = ???

      override def health(request: ClusterHealthRequest, listener: ActionListener[ClusterHealthResponse]): Unit = {
        esClient
          .health(request)
          .runAsync(handleResultUsing(listener))
      }

      override def prepareHealth(indices: String*): ClusterHealthRequestBuilder = ???

      override def state(request: ClusterStateRequest): ActionFuture[ClusterStateResponse] = ???

      override def state(request: ClusterStateRequest, listener: ActionListener[ClusterStateResponse]): Unit = {
        // todo: implement properly
        listener.onResponse(new ClusterStateResponse(
          ClusterName.DEFAULT,
          ClusterState.EMPTY_STATE,
          false
        ))
      }

      override def prepareState(): ClusterStateRequestBuilder = ???

      override def updateSettings(request: ClusterUpdateSettingsRequest): ActionFuture[ClusterUpdateSettingsResponse] = ???

      override def updateSettings(request: ClusterUpdateSettingsRequest, listener: ActionListener[ClusterUpdateSettingsResponse]): Unit = ???

      override def prepareUpdateSettings(): ClusterUpdateSettingsRequestBuilder = ???

      override def prepareReloadSecureSettings(): NodesReloadSecureSettingsRequestBuilder = ???

      override def reroute(request: ClusterRerouteRequest): ActionFuture[ClusterRerouteResponse] = ???

      override def reroute(request: ClusterRerouteRequest, listener: ActionListener[ClusterRerouteResponse]): Unit = ???

      override def prepareReroute(): ClusterRerouteRequestBuilder = ???

      override def nodesInfo(request: NodesInfoRequest): ActionFuture[NodesInfoResponse] = ???

      override def nodesInfo(request: NodesInfoRequest, listener: ActionListener[NodesInfoResponse]): Unit = ???

      override def prepareNodesInfo(nodesIds: String*): NodesInfoRequestBuilder = ???

      override def clusterStats(request: ClusterStatsRequest): ActionFuture[ClusterStatsResponse] = ???

      override def clusterStats(request: ClusterStatsRequest, listener: ActionListener[ClusterStatsResponse]): Unit = ???

      override def prepareClusterStats(): ClusterStatsRequestBuilder = ???

      override def nodesStats(request: NodesStatsRequest): ActionFuture[NodesStatsResponse] = ???

      override def nodesStats(request: NodesStatsRequest, listener: ActionListener[NodesStatsResponse]): Unit = ???

      override def prepareNodesStats(nodesIds: String*): NodesStatsRequestBuilder = ???

      override def nodesUsage(request: NodesUsageRequest): ActionFuture[NodesUsageResponse] = ???

      override def nodesUsage(request: NodesUsageRequest, listener: ActionListener[NodesUsageResponse]): Unit = ???

      override def prepareNodesUsage(nodesIds: String*): NodesUsageRequestBuilder = ???

      override def nodesHotThreads(request: NodesHotThreadsRequest): ActionFuture[NodesHotThreadsResponse] = ???

      override def nodesHotThreads(request: NodesHotThreadsRequest, listener: ActionListener[NodesHotThreadsResponse]): Unit = ???

      override def prepareNodesHotThreads(nodesIds: String*): NodesHotThreadsRequestBuilder = ???

      override def listTasks(request: ListTasksRequest): ActionFuture[ListTasksResponse] = ???

      override def listTasks(request: ListTasksRequest, listener: ActionListener[ListTasksResponse]): Unit = ???

      override def prepareListTasks(nodesIds: String*): ListTasksRequestBuilder = ???

      override def getTask(request: GetTaskRequest): ActionFuture[GetTaskResponse] = ???

      override def getTask(request: GetTaskRequest, listener: ActionListener[GetTaskResponse]): Unit = ???

      override def prepareGetTask(taskId: String): GetTaskRequestBuilder = ???

      override def prepareGetTask(taskId: TaskId): GetTaskRequestBuilder = ???

      override def cancelTasks(request: CancelTasksRequest): ActionFuture[CancelTasksResponse] = ???

      override def cancelTasks(request: CancelTasksRequest, listener: ActionListener[CancelTasksResponse]): Unit = ???

      override def prepareCancelTasks(nodesIds: String*): CancelTasksRequestBuilder = ???

      override def searchShards(request: ClusterSearchShardsRequest): ActionFuture[ClusterSearchShardsResponse] = ???

      override def searchShards(request: ClusterSearchShardsRequest, listener: ActionListener[ClusterSearchShardsResponse]): Unit = ???

      override def prepareSearchShards(): ClusterSearchShardsRequestBuilder = ???

      override def prepareSearchShards(indices: String*): ClusterSearchShardsRequestBuilder = ???

      override def putRepository(request: PutRepositoryRequest): ActionFuture[AcknowledgedResponse] = ???

      override def putRepository(request: PutRepositoryRequest, listener: ActionListener[AcknowledgedResponse]): Unit = ???

      override def preparePutRepository(name: String): PutRepositoryRequestBuilder = ???

      override def deleteRepository(request: DeleteRepositoryRequest): ActionFuture[AcknowledgedResponse] = ???

      override def deleteRepository(request: DeleteRepositoryRequest, listener: ActionListener[AcknowledgedResponse]): Unit = ???

      override def prepareDeleteRepository(name: String): DeleteRepositoryRequestBuilder = ???

      override def getRepositories(request: GetRepositoriesRequest): ActionFuture[GetRepositoriesResponse] = ???

      override def getRepositories(request: GetRepositoriesRequest, listener: ActionListener[GetRepositoriesResponse]): Unit = ???

      override def prepareGetRepositories(name: String*): GetRepositoriesRequestBuilder = ???

      override def prepareCleanupRepository(repository: String): CleanupRepositoryRequestBuilder = ???

      override def cleanupRepository(repository: CleanupRepositoryRequest): ActionFuture[CleanupRepositoryResponse] = ???

      override def cleanupRepository(repository: CleanupRepositoryRequest, listener: ActionListener[CleanupRepositoryResponse]): Unit = ???

      override def verifyRepository(request: VerifyRepositoryRequest): ActionFuture[VerifyRepositoryResponse] = ???

      override def verifyRepository(request: VerifyRepositoryRequest, listener: ActionListener[VerifyRepositoryResponse]): Unit = ???

      override def prepareVerifyRepository(name: String): VerifyRepositoryRequestBuilder = ???

      override def createSnapshot(request: CreateSnapshotRequest): ActionFuture[CreateSnapshotResponse] = ???

      override def createSnapshot(request: CreateSnapshotRequest, listener: ActionListener[CreateSnapshotResponse]): Unit = ???

      override def prepareCreateSnapshot(repository: String, name: String): CreateSnapshotRequestBuilder = ???

      override def getSnapshots(request: GetSnapshotsRequest): ActionFuture[GetSnapshotsResponse] = ???

      override def getSnapshots(request: GetSnapshotsRequest, listener: ActionListener[GetSnapshotsResponse]): Unit = ???

      override def prepareGetSnapshots(repository: String): GetSnapshotsRequestBuilder = ???

      override def deleteSnapshot(request: DeleteSnapshotRequest): ActionFuture[AcknowledgedResponse] = ???

      override def deleteSnapshot(request: DeleteSnapshotRequest, listener: ActionListener[AcknowledgedResponse]): Unit = ???

      override def prepareDeleteSnapshot(repository: String, snapshot: String): DeleteSnapshotRequestBuilder = ???

      override def restoreSnapshot(request: RestoreSnapshotRequest): ActionFuture[RestoreSnapshotResponse] = ???

      override def restoreSnapshot(request: RestoreSnapshotRequest, listener: ActionListener[RestoreSnapshotResponse]): Unit = ???

      override def prepareRestoreSnapshot(repository: String, snapshot: String): RestoreSnapshotRequestBuilder = ???

      override def pendingClusterTasks(request: PendingClusterTasksRequest, listener: ActionListener[PendingClusterTasksResponse]): Unit = ???

      override def pendingClusterTasks(request: PendingClusterTasksRequest): ActionFuture[PendingClusterTasksResponse] = ???

      override def preparePendingClusterTasks(): PendingClusterTasksRequestBuilder = ???

      override def snapshotsStatus(request: SnapshotsStatusRequest): ActionFuture[SnapshotsStatusResponse] = ???

      override def snapshotsStatus(request: SnapshotsStatusRequest, listener: ActionListener[SnapshotsStatusResponse]): Unit = ???

      override def prepareSnapshotStatus(repository: String): SnapshotsStatusRequestBuilder = ???

      override def prepareSnapshotStatus(): SnapshotsStatusRequestBuilder = ???

      override def putPipeline(request: PutPipelineRequest, listener: ActionListener[AcknowledgedResponse]): Unit = ???

      override def putPipeline(request: PutPipelineRequest): ActionFuture[AcknowledgedResponse] = ???

      override def preparePutPipeline(id: String, source: BytesReference, xContentType: XContentType): PutPipelineRequestBuilder = ???

      override def deletePipeline(request: DeletePipelineRequest, listener: ActionListener[AcknowledgedResponse]): Unit = ???

      override def deletePipeline(request: DeletePipelineRequest): ActionFuture[AcknowledgedResponse] = ???

      override def prepareDeletePipeline(): DeletePipelineRequestBuilder = ???

      override def prepareDeletePipeline(id: String): DeletePipelineRequestBuilder = ???

      override def getPipeline(request: GetPipelineRequest, listener: ActionListener[GetPipelineResponse]): Unit = ???

      override def getPipeline(request: GetPipelineRequest): ActionFuture[GetPipelineResponse] = ???

      override def prepareGetPipeline(ids: String*): GetPipelineRequestBuilder = ???

      override def simulatePipeline(request: SimulatePipelineRequest, listener: ActionListener[SimulatePipelineResponse]): Unit = ???

      override def simulatePipeline(request: SimulatePipelineRequest): ActionFuture[SimulatePipelineResponse] = ???

      override def prepareSimulatePipeline(source: BytesReference, xContentType: XContentType): SimulatePipelineRequestBuilder = ???

      override def allocationExplain(request: ClusterAllocationExplainRequest, listener: ActionListener[ClusterAllocationExplainResponse]): Unit = ???

      override def allocationExplain(request: ClusterAllocationExplainRequest): ActionFuture[ClusterAllocationExplainResponse] = ???

      override def prepareAllocationExplain(): ClusterAllocationExplainRequestBuilder = ???

      override def preparePutStoredScript(): PutStoredScriptRequestBuilder = ???

      override def deleteStoredScript(request: DeleteStoredScriptRequest, listener: ActionListener[AcknowledgedResponse]): Unit = ???

      override def deleteStoredScript(request: DeleteStoredScriptRequest): ActionFuture[AcknowledgedResponse] = ???

      override def prepareDeleteStoredScript(): DeleteStoredScriptRequestBuilder = ???

      override def prepareDeleteStoredScript(id: String): DeleteStoredScriptRequestBuilder = ???

      override def putStoredScript(request: PutStoredScriptRequest, listener: ActionListener[AcknowledgedResponse]): Unit = ???

      override def putStoredScript(request: PutStoredScriptRequest): ActionFuture[AcknowledgedResponse] = ???

      override def prepareGetStoredScript(): GetStoredScriptRequestBuilder = ???

      override def prepareGetStoredScript(id: String): GetStoredScriptRequestBuilder = ???

      override def getStoredScript(request: GetStoredScriptRequest, listener: ActionListener[GetStoredScriptResponse]): Unit = ???

      override def getStoredScript(request: GetStoredScriptRequest): ActionFuture[GetStoredScriptResponse] = ???

      override def execute[Request <: ActionRequest, Response <: ActionResponse](action: ActionType[Response], request: Request): ActionFuture[Response] = ???

      override def execute[Request <: ActionRequest, Response <: ActionResponse](action: ActionType[Response], request: Request, listener: ActionListener[Response]): Unit = ???

      override def threadPool(): ThreadPool = ???
    }

    override def indices(): IndicesAdminClient = new IndicesAdminClient {
      override def exists(request: IndicesExistsRequest): ActionFuture[IndicesExistsResponse] = ???

      override def exists(request: IndicesExistsRequest, listener: ActionListener[IndicesExistsResponse]): Unit = ???

      override def prepareExists(indices: String*): IndicesExistsRequestBuilder = ???

      override def typesExists(request: TypesExistsRequest): ActionFuture[TypesExistsResponse] = ???

      override def typesExists(request: TypesExistsRequest, listener: ActionListener[TypesExistsResponse]): Unit = ???

      override def prepareTypesExists(index: String*): TypesExistsRequestBuilder = ???

      override def stats(request: IndicesStatsRequest): ActionFuture[IndicesStatsResponse] = ???

      override def stats(request: IndicesStatsRequest, listener: ActionListener[IndicesStatsResponse]): Unit = {
        esClient
          .stats(request)
          .runAsync(handleResultUsing(listener))
      }

      override def prepareStats(indices: String*): IndicesStatsRequestBuilder = ???

      override def recoveries(request: RecoveryRequest): ActionFuture[RecoveryResponse] = ???

      override def recoveries(request: RecoveryRequest, listener: ActionListener[RecoveryResponse]): Unit = ???

      override def prepareRecoveries(indices: String*): RecoveryRequestBuilder = ???

      override def segments(request: IndicesSegmentsRequest): ActionFuture[IndicesSegmentResponse] = ???

      override def segments(request: IndicesSegmentsRequest, listener: ActionListener[IndicesSegmentResponse]): Unit = ???

      override def prepareSegments(indices: String*): IndicesSegmentsRequestBuilder = ???

      override def shardStores(request: IndicesShardStoresRequest): ActionFuture[IndicesShardStoresResponse] = ???

      override def shardStores(request: IndicesShardStoresRequest, listener: ActionListener[IndicesShardStoresResponse]): Unit = ???

      override def prepareShardStores(indices: String*): IndicesShardStoreRequestBuilder = ???

      override def create(request: CreateIndexRequest): ActionFuture[CreateIndexResponse] = ???

      override def create(request: CreateIndexRequest, listener: ActionListener[CreateIndexResponse]): Unit = ???

      override def prepareCreate(index: String): CreateIndexRequestBuilder = ???

      override def delete(request: DeleteIndexRequest): ActionFuture[AcknowledgedResponse] = ???

      override def delete(request: DeleteIndexRequest, listener: ActionListener[AcknowledgedResponse]): Unit = ???

      override def prepareDelete(indices: String*): DeleteIndexRequestBuilder = ???

      override def close(request: CloseIndexRequest): ActionFuture[CloseIndexResponse] = ???

      override def close(request: CloseIndexRequest, listener: ActionListener[CloseIndexResponse]): Unit = ???

      override def prepareClose(indices: String*): CloseIndexRequestBuilder = ???

      override def open(request: OpenIndexRequest): ActionFuture[OpenIndexResponse] = ???

      override def open(request: OpenIndexRequest, listener: ActionListener[OpenIndexResponse]): Unit = ???

      override def prepareOpen(indices: String*): OpenIndexRequestBuilder = ???

      override def refresh(request: RefreshRequest): ActionFuture[RefreshResponse] = ???

      override def refresh(request: RefreshRequest, listener: ActionListener[RefreshResponse]): Unit = ???

      override def prepareRefresh(indices: String*): RefreshRequestBuilder = ???

      override def flush(request: FlushRequest): ActionFuture[FlushResponse] = ???

      override def flush(request: FlushRequest, listener: ActionListener[FlushResponse]): Unit = ???

      override def prepareFlush(indices: String*): FlushRequestBuilder = ???

      override def syncedFlush(request: SyncedFlushRequest): ActionFuture[SyncedFlushResponse] = ???

      override def syncedFlush(request: SyncedFlushRequest, listener: ActionListener[SyncedFlushResponse]): Unit = ???

      override def prepareSyncedFlush(indices: String*): SyncedFlushRequestBuilder = ???

      override def forceMerge(request: ForceMergeRequest): ActionFuture[ForceMergeResponse] = ???

      override def forceMerge(request: ForceMergeRequest, listener: ActionListener[ForceMergeResponse]): Unit = ???

      override def prepareForceMerge(indices: String*): ForceMergeRequestBuilder = ???

      override def upgrade(request: UpgradeRequest): ActionFuture[UpgradeResponse] = ???

      override def upgrade(request: UpgradeRequest, listener: ActionListener[UpgradeResponse]): Unit = ???

      override def prepareUpgradeStatus(indices: String*): UpgradeStatusRequestBuilder = ???

      override def upgradeStatus(request: UpgradeStatusRequest): ActionFuture[UpgradeStatusResponse] = ???

      override def upgradeStatus(request: UpgradeStatusRequest, listener: ActionListener[UpgradeStatusResponse]): Unit = ???

      override def prepareUpgrade(indices: String*): UpgradeRequestBuilder = ???

      override def getMappings(request: GetMappingsRequest, listener: ActionListener[GetMappingsResponse]): Unit = ???

      override def getMappings(request: GetMappingsRequest): ActionFuture[GetMappingsResponse] = ???

      override def prepareGetMappings(indices: String*): GetMappingsRequestBuilder = ???

      override def getFieldMappings(request: GetFieldMappingsRequest, listener: ActionListener[GetFieldMappingsResponse]): Unit = ???

      override def prepareGetFieldMappings(indices: String*): GetFieldMappingsRequestBuilder = ???

      override def getFieldMappings(request: GetFieldMappingsRequest): ActionFuture[GetFieldMappingsResponse] = ???

      override def putMapping(request: PutMappingRequest): ActionFuture[AcknowledgedResponse] = ???

      override def putMapping(request: PutMappingRequest, listener: ActionListener[AcknowledgedResponse]): Unit = ???

      override def preparePutMapping(indices: String*): PutMappingRequestBuilder = ???

      override def aliases(request: IndicesAliasesRequest): ActionFuture[AcknowledgedResponse] = ???

      override def aliases(request: IndicesAliasesRequest, listener: ActionListener[AcknowledgedResponse]): Unit = ???

      override def prepareAliases(): IndicesAliasesRequestBuilder = ???

      override def getAliases(request: GetAliasesRequest): ActionFuture[GetAliasesResponse] = ???

      override def getAliases(request: GetAliasesRequest, listener: ActionListener[GetAliasesResponse]): Unit = ???

      override def prepareGetAliases(aliases: String*): GetAliasesRequestBuilder = ???

      override def prepareAliasesExist(aliases: String*): AliasesExistRequestBuilder = ???

      override def aliasesExist(request: GetAliasesRequest): ActionFuture[AliasesExistResponse] = ???

      override def aliasesExist(request: GetAliasesRequest, listener: ActionListener[AliasesExistResponse]): Unit = ???

      override def getIndex(request: GetIndexRequest): ActionFuture[GetIndexResponse] = ???

      override def getIndex(request: GetIndexRequest, listener: ActionListener[GetIndexResponse]): Unit = ???

      override def prepareGetIndex(): GetIndexRequestBuilder = ???

      override def clearCache(request: ClearIndicesCacheRequest): ActionFuture[ClearIndicesCacheResponse] = ???

      override def clearCache(request: ClearIndicesCacheRequest, listener: ActionListener[ClearIndicesCacheResponse]): Unit = ???

      override def prepareClearCache(indices: String*): ClearIndicesCacheRequestBuilder = ???

      override def updateSettings(request: UpdateSettingsRequest): ActionFuture[AcknowledgedResponse] = ???

      override def updateSettings(request: UpdateSettingsRequest, listener: ActionListener[AcknowledgedResponse]): Unit = ???

      override def prepareUpdateSettings(indices: String*): UpdateSettingsRequestBuilder = ???

      override def analyze(request: AnalyzeAction.Request): ActionFuture[AnalyzeAction.Response] = ???

      override def analyze(request: AnalyzeAction.Request, listener: ActionListener[AnalyzeAction.Response]): Unit = ???

      override def prepareAnalyze(index: String, text: String): AnalyzeRequestBuilder = ???

      override def prepareAnalyze(text: String): AnalyzeRequestBuilder = ???

      override def prepareAnalyze(): AnalyzeRequestBuilder = ???

      override def putTemplate(request: PutIndexTemplateRequest): ActionFuture[AcknowledgedResponse] = ???

      override def putTemplate(request: PutIndexTemplateRequest, listener: ActionListener[AcknowledgedResponse]): Unit = ???

      override def preparePutTemplate(name: String): PutIndexTemplateRequestBuilder = ???

      override def deleteTemplate(request: DeleteIndexTemplateRequest): ActionFuture[AcknowledgedResponse] = ???

      override def deleteTemplate(request: DeleteIndexTemplateRequest, listener: ActionListener[AcknowledgedResponse]): Unit = ???

      override def prepareDeleteTemplate(name: String): DeleteIndexTemplateRequestBuilder = ???

      override def getTemplates(request: GetIndexTemplatesRequest): ActionFuture[GetIndexTemplatesResponse] = ???

      override def getTemplates(request: GetIndexTemplatesRequest, listener: ActionListener[GetIndexTemplatesResponse]): Unit = ???

      override def prepareGetTemplates(name: String*): GetIndexTemplatesRequestBuilder = ???

      override def validateQuery(request: ValidateQueryRequest): ActionFuture[ValidateQueryResponse] = ???

      override def validateQuery(request: ValidateQueryRequest, listener: ActionListener[ValidateQueryResponse]): Unit = ???

      override def prepareValidateQuery(indices: String*): ValidateQueryRequestBuilder = ???

      override def getSettings(request: GetSettingsRequest, listener: ActionListener[GetSettingsResponse]): Unit = {
        esClient
          .getSettings(request)
          .runAsync(handleResultUsing(listener))
      }

      override def getSettings(request: GetSettingsRequest): ActionFuture[GetSettingsResponse] = ???

      override def prepareGetSettings(indices: String*): GetSettingsRequestBuilder = ???

      override def prepareResizeIndex(sourceIndex: String, targetIndex: String): ResizeRequestBuilder = ???

      override def resizeIndex(request: ResizeRequest): ActionFuture[ResizeResponse] = ???

      override def resizeIndex(request: ResizeRequest, listener: ActionListener[ResizeResponse]): Unit = ???

      override def prepareRolloverIndex(sourceAlias: String): RolloverRequestBuilder = ???

      override def rolloversIndex(request: RolloverRequest): ActionFuture[RolloverResponse] = ???

      override def rolloverIndex(request: RolloverRequest, listener: ActionListener[RolloverResponse]): Unit = ???

      override def execute[Request <: ActionRequest, Response <: ActionResponse](action: ActionType[Response], request: Request): ActionFuture[Response] = ???

      override def execute[Request <: ActionRequest, Response <: ActionResponse](action: ActionType[Response], request: Request, listener: ActionListener[Response]): Unit = ???

      override def threadPool(): ThreadPool = ???
    }
  }
  on(this).set("rorAdmin", customAdminClient)

  override def initialize(actions: util.Map[ActionType[_ <: ActionResponse], TransportAction[_ <: ActionRequest, _ <: ActionResponse]],
                          localNodeId: Supplier[String],
                          remoteClusterService: RemoteClusterService): Unit = {
    super.initialize(actions, localNodeId, remoteClusterService)
  }

  override def close(): Unit = {
    super.close()
  }

  override def doExecute[Request <: ActionRequest, Response <: ActionResponse](action: ActionType[Response],
                                                                               request: Request,
                                                                               listener: ActionListener[Response]): Unit = {
    super.doExecute(action, request, listener)
  }

  override def executeLocally[Request <: ActionRequest, Response <: ActionResponse](action: ActionType[Response],
                                                                                    request: Request,
                                                                                    listener: ActionListener[Response]): Task = {
    super.executeLocally(action, request, listener)
  }

  override def executeLocally[Request <: ActionRequest, Response <: ActionResponse](action: ActionType[Response],
                                                                                    request: Request,
                                                                                    listener: TaskListener[Response]): Task = {
    super.executeLocally(action, request, listener)
  }

  override def getLocalNodeId: String = {
    super.getLocalNodeId
  }

  override def getRemoteClusterClient(clusterAlias: String): Client = {
    super.getRemoteClusterClient(clusterAlias)
  }

  private def handleResultUsing[T](listener: ActionListener[T])
                                  (result: Either[Throwable, T]): Unit = result match {
    case Right(response) => listener.onResponse(response)
    case Left(ex: Exception) => listener.onFailure(ex)
    case Left(ex: Throwable) => listener.onFailure(new RuntimeException(ex))
  }

}

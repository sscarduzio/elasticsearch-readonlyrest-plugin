package tech.beshu.ror.proxy.es.clients

import monix.execution.Scheduler
import org.elasticsearch.action.{ActionFuture, ActionListener, ActionRequest, ActionResponse, ActionType}
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
import org.elasticsearch.action.admin.cluster.storedscripts.{DeleteStoredScriptRequest, DeleteStoredScriptRequestBuilder, GetStoredScriptRequest, GetStoredScriptRequestBuilder, GetStoredScriptResponse, PutStoredScriptRequest, PutStoredScriptRequestBuilder}
import org.elasticsearch.action.admin.cluster.tasks.{PendingClusterTasksRequest, PendingClusterTasksRequestBuilder, PendingClusterTasksResponse}
import org.elasticsearch.action.ingest.{DeletePipelineRequest, DeletePipelineRequestBuilder, GetPipelineRequest, GetPipelineRequestBuilder, GetPipelineResponse, PutPipelineRequest, PutPipelineRequestBuilder, SimulatePipelineRequest, SimulatePipelineRequestBuilder, SimulatePipelineResponse}
import org.elasticsearch.action.support.master.AcknowledgedResponse
import org.elasticsearch.client.ClusterAdminClient
import org.elasticsearch.cluster.{ClusterName, ClusterState}
import org.elasticsearch.common.bytes.BytesReference
import org.elasticsearch.common.xcontent.XContentType
import org.elasticsearch.tasks.TaskId
import org.elasticsearch.threadpool.ThreadPool

class HighLevelClientBasedClusterAdminClient(esClient: RestHighLevelClientAdapter)
                                            (implicit scheduler: Scheduler)
  extends ClusterAdminClient {

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

  private def handleResultUsing[T](listener: ActionListener[T])
                                  (result: Either[Throwable, T]): Unit = result match {
    case Right(response) => listener.onResponse(response)
    case Left(ex: Exception) => listener.onFailure(ex)
    case Left(ex: Throwable) => listener.onFailure(new RuntimeException(ex))
  }
}
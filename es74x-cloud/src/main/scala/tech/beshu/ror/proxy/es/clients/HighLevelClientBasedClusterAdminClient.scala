/*
 *     Beshu Limited all rights reserved
 */
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
import tech.beshu.ror.proxy.es.exceptions.NotDefinedForRorProxy

class HighLevelClientBasedClusterAdminClient(esClient: RestHighLevelClientAdapter)
                                            (implicit scheduler: Scheduler)
  extends ClusterAdminClient {

  override def health(request: ClusterHealthRequest): ActionFuture[ClusterHealthResponse] =
    throw NotDefinedForRorProxy

  override def health(request: ClusterHealthRequest, listener: ActionListener[ClusterHealthResponse]): Unit = {
    esClient
      .health(request)
      .runAsync(handleResultUsing(listener))
  }

  override def prepareHealth(indices: String*): ClusterHealthRequestBuilder = 

  override def state(request: ClusterStateRequest): ActionFuture[ClusterStateResponse] = throw NotDefinedForRorProxy

  override def state(request: ClusterStateRequest, listener: ActionListener[ClusterStateResponse]): Unit = {
    // todo: implement properly
    listener.onResponse(new ClusterStateResponse(
      ClusterName.DEFAULT,
      ClusterState.EMPTY_STATE,
      false
    ))
  }

  override def prepareState(): ClusterStateRequestBuilder = throw NotDefinedForRorProxy

  override def updateSettings(request: ClusterUpdateSettingsRequest): ActionFuture[ClusterUpdateSettingsResponse] = throw NotDefinedForRorProxy

  override def updateSettings(request: ClusterUpdateSettingsRequest, listener: ActionListener[ClusterUpdateSettingsResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareUpdateSettings(): ClusterUpdateSettingsRequestBuilder = throw NotDefinedForRorProxy

  override def prepareReloadSecureSettings(): NodesReloadSecureSettingsRequestBuilder = throw NotDefinedForRorProxy

  override def reroute(request: ClusterRerouteRequest): ActionFuture[ClusterRerouteResponse] = throw NotDefinedForRorProxy

  override def reroute(request: ClusterRerouteRequest, listener: ActionListener[ClusterRerouteResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareReroute(): ClusterRerouteRequestBuilder = throw NotDefinedForRorProxy

  override def nodesInfo(request: NodesInfoRequest): ActionFuture[NodesInfoResponse] = throw NotDefinedForRorProxy

  override def nodesInfo(request: NodesInfoRequest, listener: ActionListener[NodesInfoResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareNodesInfo(nodesIds: String*): NodesInfoRequestBuilder = throw NotDefinedForRorProxy

  override def clusterStats(request: ClusterStatsRequest): ActionFuture[ClusterStatsResponse] = throw NotDefinedForRorProxy

  override def clusterStats(request: ClusterStatsRequest, listener: ActionListener[ClusterStatsResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareClusterStats(): ClusterStatsRequestBuilder = throw NotDefinedForRorProxy

  override def nodesStats(request: NodesStatsRequest): ActionFuture[NodesStatsResponse] = throw NotDefinedForRorProxy

  override def nodesStats(request: NodesStatsRequest, listener: ActionListener[NodesStatsResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareNodesStats(nodesIds: String*): NodesStatsRequestBuilder = throw NotDefinedForRorProxy

  override def nodesUsage(request: NodesUsageRequest): ActionFuture[NodesUsageResponse] = throw NotDefinedForRorProxy

  override def nodesUsage(request: NodesUsageRequest, listener: ActionListener[NodesUsageResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareNodesUsage(nodesIds: String*): NodesUsageRequestBuilder = throw NotDefinedForRorProxy

  override def nodesHotThreads(request: NodesHotThreadsRequest): ActionFuture[NodesHotThreadsResponse] = throw NotDefinedForRorProxy

  override def nodesHotThreads(request: NodesHotThreadsRequest, listener: ActionListener[NodesHotThreadsResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareNodesHotThreads(nodesIds: String*): NodesHotThreadsRequestBuilder = throw NotDefinedForRorProxy

  override def listTasks(request: ListTasksRequest): ActionFuture[ListTasksResponse] = throw NotDefinedForRorProxy

  override def listTasks(request: ListTasksRequest, listener: ActionListener[ListTasksResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareListTasks(nodesIds: String*): ListTasksRequestBuilder = throw NotDefinedForRorProxy

  override def getTask(request: GetTaskRequest): ActionFuture[GetTaskResponse] = throw NotDefinedForRorProxy

  override def getTask(request: GetTaskRequest, listener: ActionListener[GetTaskResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareGetTask(taskId: String): GetTaskRequestBuilder = throw NotDefinedForRorProxy

  override def prepareGetTask(taskId: TaskId): GetTaskRequestBuilder = throw NotDefinedForRorProxy

  override def cancelTasks(request: CancelTasksRequest): ActionFuture[CancelTasksResponse] = throw NotDefinedForRorProxy

  override def cancelTasks(request: CancelTasksRequest, listener: ActionListener[CancelTasksResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareCancelTasks(nodesIds: String*): CancelTasksRequestBuilder = throw NotDefinedForRorProxy

  override def searchShards(request: ClusterSearchShardsRequest): ActionFuture[ClusterSearchShardsResponse] = throw NotDefinedForRorProxy

  override def searchShards(request: ClusterSearchShardsRequest, listener: ActionListener[ClusterSearchShardsResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareSearchShards(): ClusterSearchShardsRequestBuilder = throw NotDefinedForRorProxy

  override def prepareSearchShards(indices: String*): ClusterSearchShardsRequestBuilder = throw NotDefinedForRorProxy

  override def putRepository(request: PutRepositoryRequest): ActionFuture[AcknowledgedResponse] = throw NotDefinedForRorProxy

  override def putRepository(request: PutRepositoryRequest, listener: ActionListener[AcknowledgedResponse]): Unit = throw NotDefinedForRorProxy

  override def preparePutRepository(name: String): PutRepositoryRequestBuilder = throw NotDefinedForRorProxy

  override def deleteRepository(request: DeleteRepositoryRequest): ActionFuture[AcknowledgedResponse] = throw NotDefinedForRorProxy

  override def deleteRepository(request: DeleteRepositoryRequest, listener: ActionListener[AcknowledgedResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareDeleteRepository(name: String): DeleteRepositoryRequestBuilder = throw NotDefinedForRorProxy

  override def getRepositories(request: GetRepositoriesRequest): ActionFuture[GetRepositoriesResponse] = throw NotDefinedForRorProxy

  override def getRepositories(request: GetRepositoriesRequest, listener: ActionListener[GetRepositoriesResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareGetRepositories(name: String*): GetRepositoriesRequestBuilder = throw NotDefinedForRorProxy

  override def prepareCleanupRepository(repository: String): CleanupRepositoryRequestBuilder = throw NotDefinedForRorProxy

  override def cleanupRepository(repository: CleanupRepositoryRequest): ActionFuture[CleanupRepositoryResponse] = throw NotDefinedForRorProxy

  override def cleanupRepository(repository: CleanupRepositoryRequest, listener: ActionListener[CleanupRepositoryResponse]): Unit = throw NotDefinedForRorProxy

  override def verifyRepository(request: VerifyRepositoryRequest): ActionFuture[VerifyRepositoryResponse] = throw NotDefinedForRorProxy

  override def verifyRepository(request: VerifyRepositoryRequest, listener: ActionListener[VerifyRepositoryResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareVerifyRepository(name: String): VerifyRepositoryRequestBuilder = throw NotDefinedForRorProxy

  override def createSnapshot(request: CreateSnapshotRequest): ActionFuture[CreateSnapshotResponse] = throw NotDefinedForRorProxy

  override def createSnapshot(request: CreateSnapshotRequest, listener: ActionListener[CreateSnapshotResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareCreateSnapshot(repository: String, name: String): CreateSnapshotRequestBuilder = throw NotDefinedForRorProxy

  override def getSnapshots(request: GetSnapshotsRequest): ActionFuture[GetSnapshotsResponse] = throw NotDefinedForRorProxy

  override def getSnapshots(request: GetSnapshotsRequest, listener: ActionListener[GetSnapshotsResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareGetSnapshots(repository: String): GetSnapshotsRequestBuilder = throw NotDefinedForRorProxy

  override def deleteSnapshot(request: DeleteSnapshotRequest): ActionFuture[AcknowledgedResponse] = throw NotDefinedForRorProxy

  override def deleteSnapshot(request: DeleteSnapshotRequest, listener: ActionListener[AcknowledgedResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareDeleteSnapshot(repository: String, snapshot: String): DeleteSnapshotRequestBuilder = throw NotDefinedForRorProxy

  override def restoreSnapshot(request: RestoreSnapshotRequest): ActionFuture[RestoreSnapshotResponse] = throw NotDefinedForRorProxy

  override def restoreSnapshot(request: RestoreSnapshotRequest, listener: ActionListener[RestoreSnapshotResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareRestoreSnapshot(repository: String, snapshot: String): RestoreSnapshotRequestBuilder = throw NotDefinedForRorProxy

  override def pendingClusterTasks(request: PendingClusterTasksRequest, listener: ActionListener[PendingClusterTasksResponse]): Unit = throw NotDefinedForRorProxy

  override def pendingClusterTasks(request: PendingClusterTasksRequest): ActionFuture[PendingClusterTasksResponse] = throw NotDefinedForRorProxy

  override def preparePendingClusterTasks(): PendingClusterTasksRequestBuilder = throw NotDefinedForRorProxy

  override def snapshotsStatus(request: SnapshotsStatusRequest): ActionFuture[SnapshotsStatusResponse] = throw NotDefinedForRorProxy

  override def snapshotsStatus(request: SnapshotsStatusRequest, listener: ActionListener[SnapshotsStatusResponse]): Unit = throw NotDefinedForRorProxy

  override def prepareSnapshotStatus(repository: String): SnapshotsStatusRequestBuilder = throw NotDefinedForRorProxy

  override def prepareSnapshotStatus(): SnapshotsStatusRequestBuilder = throw NotDefinedForRorProxy

  override def putPipeline(request: PutPipelineRequest, listener: ActionListener[AcknowledgedResponse]): Unit = throw NotDefinedForRorProxy

  override def putPipeline(request: PutPipelineRequest): ActionFuture[AcknowledgedResponse] = throw NotDefinedForRorProxy

  override def preparePutPipeline(id: String, source: BytesReference, xContentType: XContentType): PutPipelineRequestBuilder = throw NotDefinedForRorProxy

  override def deletePipeline(request: DeletePipelineRequest, listener: ActionListener[AcknowledgedResponse]): Unit = throw NotDefinedForRorProxy

  override def deletePipeline(request: DeletePipelineRequest): ActionFuture[AcknowledgedResponse] = throw NotDefinedForRorProxy

  override def prepareDeletePipeline(): DeletePipelineRequestBuilder = throw NotDefinedForRorProxy

  override def prepareDeletePipeline(id: String): DeletePipelineRequestBuilder = throw NotDefinedForRorProxy

  override def getPipeline(request: GetPipelineRequest, listener: ActionListener[GetPipelineResponse]): Unit = throw NotDefinedForRorProxy

  override def getPipeline(request: GetPipelineRequest): ActionFuture[GetPipelineResponse] = throw NotDefinedForRorProxy

  override def prepareGetPipeline(ids: String*): GetPipelineRequestBuilder = throw NotDefinedForRorProxy

  override def simulatePipeline(request: SimulatePipelineRequest, listener: ActionListener[SimulatePipelineResponse]): Unit = throw NotDefinedForRorProxy

  override def simulatePipeline(request: SimulatePipelineRequest): ActionFuture[SimulatePipelineResponse] = throw NotDefinedForRorProxy

  override def prepareSimulatePipeline(source: BytesReference, xContentType: XContentType): SimulatePipelineRequestBuilder = throw NotDefinedForRorProxy

  override def allocationExplain(request: ClusterAllocationExplainRequest, listener: ActionListener[ClusterAllocationExplainResponse]): Unit = throw NotDefinedForRorProxy

  override def allocationExplain(request: ClusterAllocationExplainRequest): ActionFuture[ClusterAllocationExplainResponse] = throw NotDefinedForRorProxy

  override def prepareAllocationExplain(): ClusterAllocationExplainRequestBuilder = throw NotDefinedForRorProxy

  override def preparePutStoredScript(): PutStoredScriptRequestBuilder = throw NotDefinedForRorProxy

  override def deleteStoredScript(request: DeleteStoredScriptRequest, listener: ActionListener[AcknowledgedResponse]): Unit = throw NotDefinedForRorProxy

  override def deleteStoredScript(request: DeleteStoredScriptRequest): ActionFuture[AcknowledgedResponse] = throw NotDefinedForRorProxy

  override def prepareDeleteStoredScript(): DeleteStoredScriptRequestBuilder = throw NotDefinedForRorProxy

  override def prepareDeleteStoredScript(id: String): DeleteStoredScriptRequestBuilder = throw NotDefinedForRorProxy

  override def putStoredScript(request: PutStoredScriptRequest, listener: ActionListener[AcknowledgedResponse]): Unit = throw NotDefinedForRorProxy

  override def putStoredScript(request: PutStoredScriptRequest): ActionFuture[AcknowledgedResponse] = throw NotDefinedForRorProxy

  override def prepareGetStoredScript(): GetStoredScriptRequestBuilder = throw NotDefinedForRorProxy

  override def prepareGetStoredScript(id: String): GetStoredScriptRequestBuilder = throw NotDefinedForRorProxy

  override def getStoredScript(request: GetStoredScriptRequest, listener: ActionListener[GetStoredScriptResponse]): Unit = throw NotDefinedForRorProxy

  override def getStoredScript(request: GetStoredScriptRequest): ActionFuture[GetStoredScriptResponse] = throw NotDefinedForRorProxy

  override def execute[Request <: ActionRequest, Response <: ActionResponse](action: ActionType[Response], request: Request): ActionFuture[Response] = throw NotDefinedForRorProxy

  override def execute[Request <: ActionRequest, Response <: ActionResponse](action: ActionType[Response], request: Request, listener: ActionListener[Response]): Unit = throw NotDefinedForRorProxy

  override def threadPool(): ThreadPool = throw NotDefinedForRorProxy

  private def handleResultUsing[T](listener: ActionListener[T])
                                  (result: Either[Throwable, T]): Unit = result match {
    case Right(response) => listener.onResponse(response)
    case Left(ex: Exception) => listener.onFailure(ex)
    case Left(ex: Throwable) => listener.onFailure(new RuntimeException(ex))
  }
}
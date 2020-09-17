/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es.clients

import monix.eval.Task
import monix.execution.Scheduler
import org.elasticsearch.action._
import org.elasticsearch.action.admin.cluster.allocation._
import org.elasticsearch.action.admin.cluster.health._
import org.elasticsearch.action.admin.cluster.node.hotthreads._
import org.elasticsearch.action.admin.cluster.node.info._
import org.elasticsearch.action.admin.cluster.node.reload.NodesReloadSecureSettingsRequestBuilder
import org.elasticsearch.action.admin.cluster.node.stats._
import org.elasticsearch.action.admin.cluster.node.tasks.cancel._
import org.elasticsearch.action.admin.cluster.node.tasks.get._
import org.elasticsearch.action.admin.cluster.node.tasks.list._
import org.elasticsearch.action.admin.cluster.node.usage._
import org.elasticsearch.action.admin.cluster.repositories.cleanup._
import org.elasticsearch.action.admin.cluster.repositories.delete._
import org.elasticsearch.action.admin.cluster.repositories.get._
import org.elasticsearch.action.admin.cluster.repositories.put._
import org.elasticsearch.action.admin.cluster.repositories.verify._
import org.elasticsearch.action.admin.cluster.reroute._
import org.elasticsearch.action.admin.cluster.settings._
import org.elasticsearch.action.admin.cluster.shards._
import org.elasticsearch.action.admin.cluster.snapshots.create._
import org.elasticsearch.action.admin.cluster.snapshots.delete._
import org.elasticsearch.action.admin.cluster.snapshots.get._
import org.elasticsearch.action.admin.cluster.snapshots.restore._
import org.elasticsearch.action.admin.cluster.snapshots.status._
import org.elasticsearch.action.admin.cluster.state._
import org.elasticsearch.action.admin.cluster.stats._
import org.elasticsearch.action.admin.cluster.storedscripts._
import org.elasticsearch.action.admin.cluster.tasks._
import org.elasticsearch.action.ingest._
import org.elasticsearch.action.support.master.AcknowledgedResponse
import org.elasticsearch.client.ClusterAdminClient
import org.elasticsearch.cluster.{ClusterName, ClusterState}
import org.elasticsearch.common.bytes.BytesReference
import org.elasticsearch.common.xcontent.XContentType
import org.elasticsearch.tasks.TaskId
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.proxy.es.ProxyIndexLevelActionFilter
import tech.beshu.ror.proxy.es.exceptions.NotDefinedForRorProxy

class HighLevelClientBasedClusterAdminClient(esClient: RestHighLevelClientAdapter,
                                             override val proxyFilter: ProxyIndexLevelActionFilter)
                                            (implicit override val scheduler: Scheduler)
  extends ClusterAdminClient with ProxyFilterable {

  override def health(request: ClusterHealthRequest): ActionFuture[ClusterHealthResponse] =
    throw NotDefinedForRorProxy

  override def health(request: ClusterHealthRequest, listener: ActionListener[ClusterHealthResponse]): Unit = {
    execute(ClusterHealthAction.INSTANCE.name(), request, listener) {
      esClient.health
    }
  }

  override def prepareHealth(indices: String*): ClusterHealthRequestBuilder = throw NotDefinedForRorProxy

  override def state(request: ClusterStateRequest): ActionFuture[ClusterStateResponse] = throw NotDefinedForRorProxy

  override def state(request: ClusterStateRequest, listener: ActionListener[ClusterStateResponse]): Unit = {
    execute(ClusterStateAction.INSTANCE.name(), request, listener) { _ =>
      // todo: implement properly
      Task.now(new ClusterStateResponse(
        ClusterName.DEFAULT,
        ClusterState.EMPTY_STATE,
        false
      ))
    }
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

  override def putRepository(request: PutRepositoryRequest, listener: ActionListener[AcknowledgedResponse]): Unit = {
    execute(PutRepositoryAction.INSTANCE.name(), request, listener) {
      esClient.putRepository
    }
  }

  override def preparePutRepository(name: String): PutRepositoryRequestBuilder = throw NotDefinedForRorProxy

  override def deleteRepository(request: DeleteRepositoryRequest): ActionFuture[AcknowledgedResponse] = throw NotDefinedForRorProxy

  override def deleteRepository(request: DeleteRepositoryRequest, listener: ActionListener[AcknowledgedResponse]): Unit = {
    execute(DeleteRepositoryAction.INSTANCE.name(), request, listener) {
      esClient.deleteRepository
    }
  }

  override def prepareDeleteRepository(name: String): DeleteRepositoryRequestBuilder = throw NotDefinedForRorProxy

  override def getRepositories(request: GetRepositoriesRequest): ActionFuture[GetRepositoriesResponse] = throw NotDefinedForRorProxy

  override def getRepositories(request: GetRepositoriesRequest, listener: ActionListener[GetRepositoriesResponse]): Unit = {
    execute(GetRepositoriesAction.INSTANCE.name(), request, listener) {
      esClient.getRepositories
    }
  }

  override def prepareGetRepositories(name: String*): GetRepositoriesRequestBuilder = throw NotDefinedForRorProxy

  override def prepareCleanupRepository(repository: String): CleanupRepositoryRequestBuilder = throw NotDefinedForRorProxy

  override def cleanupRepository(repository: CleanupRepositoryRequest): ActionFuture[CleanupRepositoryResponse] = throw NotDefinedForRorProxy

  override def cleanupRepository(request: CleanupRepositoryRequest, listener: ActionListener[CleanupRepositoryResponse]): Unit = {
    execute(CleanupRepositoryAction.INSTANCE.name(), request, listener) {
      esClient.cleanupRepository
    }
  }

  override def verifyRepository(request: VerifyRepositoryRequest): ActionFuture[VerifyRepositoryResponse] = throw NotDefinedForRorProxy

  override def verifyRepository(request: VerifyRepositoryRequest, listener: ActionListener[VerifyRepositoryResponse]): Unit = {
    execute(VerifyRepositoryAction.INSTANCE.name(), request, listener) {
      esClient.verifyRepository
    }
  }

  override def prepareVerifyRepository(name: String): VerifyRepositoryRequestBuilder = throw NotDefinedForRorProxy

  override def createSnapshot(request: CreateSnapshotRequest): ActionFuture[CreateSnapshotResponse] = throw NotDefinedForRorProxy

  override def createSnapshot(request: CreateSnapshotRequest, listener: ActionListener[CreateSnapshotResponse]): Unit = {
    execute(CreateSnapshotAction.INSTANCE.name(), request, listener) {
      esClient.createSnapshot
    }
  }

  override def prepareCreateSnapshot(repository: String, name: String): CreateSnapshotRequestBuilder = throw NotDefinedForRorProxy

  override def getSnapshots(request: GetSnapshotsRequest): ActionFuture[GetSnapshotsResponse] = throw NotDefinedForRorProxy

  override def getSnapshots(request: GetSnapshotsRequest, listener: ActionListener[GetSnapshotsResponse]): Unit = {
    execute(GetSnapshotsAction.INSTANCE.name(), request, listener) {
      esClient.getSnapshots
    }
  }

  override def prepareGetSnapshots(repository: String): GetSnapshotsRequestBuilder = throw NotDefinedForRorProxy

  override def deleteSnapshot(request: DeleteSnapshotRequest): ActionFuture[AcknowledgedResponse] = throw NotDefinedForRorProxy

  override def deleteSnapshot(request: DeleteSnapshotRequest, listener: ActionListener[AcknowledgedResponse]): Unit = {
    execute(DeleteSnapshotAction.INSTANCE.name(), request, listener) {
      esClient.deleteSnapshot
    }
  }

  override def restoreSnapshot(request: RestoreSnapshotRequest): ActionFuture[RestoreSnapshotResponse] = throw NotDefinedForRorProxy

  override def restoreSnapshot(request: RestoreSnapshotRequest, listener: ActionListener[RestoreSnapshotResponse]): Unit = {
    execute(RestoreSnapshotAction.INSTANCE.name(), request, listener) {
      esClient.restoreSnapshot
    }
  }

  override def prepareRestoreSnapshot(repository: String, snapshot: String): RestoreSnapshotRequestBuilder = throw NotDefinedForRorProxy

  override def pendingClusterTasks(request: PendingClusterTasksRequest, listener: ActionListener[PendingClusterTasksResponse]): Unit = throw NotDefinedForRorProxy

  override def pendingClusterTasks(request: PendingClusterTasksRequest): ActionFuture[PendingClusterTasksResponse] = throw NotDefinedForRorProxy

  override def preparePendingClusterTasks(): PendingClusterTasksRequestBuilder = throw NotDefinedForRorProxy

  override def prepareDeleteSnapshot(repository: String, snapshot: String*): DeleteSnapshotRequestBuilder = throw NotDefinedForRorProxy

  override def snapshotsStatus(request: SnapshotsStatusRequest): ActionFuture[SnapshotsStatusResponse] = throw NotDefinedForRorProxy

  override def snapshotsStatus(request: SnapshotsStatusRequest, listener: ActionListener[SnapshotsStatusResponse]): Unit = {
    execute(SnapshotsStatusAction.INSTANCE.name(), request, listener) {
      esClient.snapshotsStatus
    }
  }

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

  override def deleteStoredScript(request: DeleteStoredScriptRequest, listener: ActionListener[AcknowledgedResponse]): Unit = {
    execute(DeleteStoredScriptAction.INSTANCE.name(), request, listener) {
      esClient.deleteScript
    }
  }

  override def deleteStoredScript(request: DeleteStoredScriptRequest): ActionFuture[AcknowledgedResponse] = throw NotDefinedForRorProxy

  override def prepareDeleteStoredScript(): DeleteStoredScriptRequestBuilder = throw NotDefinedForRorProxy

  override def prepareDeleteStoredScript(id: String): DeleteStoredScriptRequestBuilder = throw NotDefinedForRorProxy

  override def putStoredScript(request: PutStoredScriptRequest, listener: ActionListener[AcknowledgedResponse]): Unit = {
    execute(PutStoredScriptAction.INSTANCE.name(), request, listener) {
      esClient.putScript
    }
  }

  override def putStoredScript(request: PutStoredScriptRequest): ActionFuture[AcknowledgedResponse] = throw NotDefinedForRorProxy

  override def prepareGetStoredScript(): GetStoredScriptRequestBuilder = throw NotDefinedForRorProxy

  override def prepareGetStoredScript(id: String): GetStoredScriptRequestBuilder = throw NotDefinedForRorProxy

  override def getStoredScript(request: GetStoredScriptRequest, listener: ActionListener[GetStoredScriptResponse]): Unit = {
    execute(GetStoredScriptAction.INSTANCE.name(), request, listener) {
      esClient.getScript
    }
  }

  override def getStoredScript(request: GetStoredScriptRequest): ActionFuture[GetStoredScriptResponse] = throw NotDefinedForRorProxy

  override def execute[Request <: ActionRequest, Response <: ActionResponse](action: ActionType[Response], request: Request): ActionFuture[Response] = throw NotDefinedForRorProxy

  override def execute[Request <: ActionRequest, Response <: ActionResponse](action: ActionType[Response], request: Request, listener: ActionListener[Response]): Unit = throw NotDefinedForRorProxy

  override def threadPool(): ThreadPool = throw NotDefinedForRorProxy

}
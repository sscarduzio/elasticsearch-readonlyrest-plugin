package tech.beshu.ror.es.request.context

import monix.eval.Task
import monix.execution.Scheduler
import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotRequest
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsRequest
import org.elasticsearch.action.admin.indices.shards.IndicesShardStoresRequest
import org.elasticsearch.action.admin.indices.stats.IndicesStatsRequest
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest
import org.elasticsearch.action.bulk.BulkRequest
import org.elasticsearch.action.delete.DeleteRequest
import org.elasticsearch.action.get.MultiGetRequest
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.action.search.{MultiSearchRequest, SearchRequest}
import org.elasticsearch.action.support.ActionFilterChain
import org.elasticsearch.action.termvectors.MultiTermVectorsRequest
import org.elasticsearch.action.{ActionListener, ActionRequest, ActionResponse, IndicesRequest}
import org.elasticsearch.index.reindex.ReindexRequest
import org.elasticsearch.rest.RestChannel
import org.elasticsearch.tasks.{Task => EsTask}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.Operation.CurrentUserMetadataOperation
import tech.beshu.ror.boot.Engine
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.context.operations._
import tech.beshu.ror.es.request.handler.regular.RegularRequestHandler
import tech.beshu.ror.es.request.handler.usermetadata.CurrentUserMetadataRequestHandler
import tech.beshu.ror.es.rradmin.RRAdminRequest

import scala.reflect.ClassTag

class AclAwareRequestFilter(clusterService: RorClusterService,
                            threadPool: ThreadPool)
                           (implicit scheduler: Scheduler) {

  def handle(engine: Engine,
             channel: RestChannel,
             task: EsTask,
             actionType: String,
             actionRequest: ActionRequest,
             listener: ActionListener[ActionResponse],
             chain: ActionFilterChain[ActionRequest, ActionResponse],
             crossClusterSearchEnabled: Boolean): Task[Unit] =
    actionRequest match {
      case request: RRAdminRequest =>
        val handler = new CurrentUserMetadataRequestHandler(engine, task, actionType, request, listener, chain, channel, threadPool)
        handler.handle(new CurrentUserMetadataOperationEsRequestContext(
          channel, task.getId, actionType, CurrentUserMetadataOperation, request, clusterService, threadPool, crossClusterSearchEnabled
        ))
      case request: PutIndexTemplateRequest =>
        val handler = new RegularRequestHandler[CreateIndexTemplateOperationBlockContext, CreateIndexTemplateOperation](
          engine, task, actionType, request, listener, chain, channel, threadPool
        )
        handler.handle(new CreateTemplateOperationEsRequestContext(
          channel, task.getId, actionType, CreateIndexTemplateOperation.from(request), request, clusterService, threadPool, crossClusterSearchEnabled
        ))
      // todo: more template requests
      case request: IndexRequest =>
        val handler = new RegularRequestHandler[IndexOperationBlockContext, IndexOperation](
          engine, task, actionType, request, listener, chain, channel, threadPool
        )
        handler.handle(new IndexOperationEsRequestContext(
          channel, task.getId, actionType, IndexOperation.from(request), request, clusterService, threadPool, crossClusterSearchEnabled
        ))
      case request: MultiGetRequest =>
        val handler = new RegularRequestHandler[MultiGetOperationBlockContext, MultiGetOperation](
          engine, task, actionType, request, listener, chain, channel, threadPool
        )
        handler.handle(new MultiGetOperationEsRequestContext(
          channel, task.getId, actionType, MultiGetOperation.from(request), request, clusterService, threadPool, crossClusterSearchEnabled
        ))
      case request: SearchRequest =>
        val handler = new RegularRequestHandler[SearchOperationBlockContext, SearchOperation](
          engine, task, actionType, request, listener, chain, channel, threadPool
        )
        handler.handle(new SearchOperationEsRequestContext(
          channel, task.getId, actionType, SearchOperation.from(request), request, clusterService, threadPool, crossClusterSearchEnabled
        ))
      case request: MultiSearchRequest =>
        val handler = new RegularRequestHandler[MultiSearchOperationBlockContext, MultiSearchOperation](
          engine, task, actionType, request, listener, chain, channel, threadPool
        )
        handler.handle(new MultiSearchOperationEsRequestContext(
          channel, task.getId, actionType, MultiSearchOperation.from(request), request, clusterService, threadPool, crossClusterSearchEnabled
        ))
      case request: MultiTermVectorsRequest =>
        val handler = new RegularRequestHandler[MultiTermVectorsOperationBlockContext, MultiTermVectorsOperation](
          engine, task, actionType, request, listener, chain, channel, threadPool
        )
        handler.handle(new MultiTermVectorsOperationEsRequestContext(
          channel, task.getId, actionType, MultiTermVectorsOperation.from(request), request, clusterService, threadPool, crossClusterSearchEnabled
        ))
      case request: BulkRequest =>
        val handler = new RegularRequestHandler[BulkOperationBlockContext, BulkOperation](
          engine, task, actionType, request, listener, chain, channel, threadPool
        )
        handler.handle(new BulkOperationEsRequestContext(
          channel, task.getId, actionType, BulkOperation.from(request), request, clusterService, threadPool, crossClusterSearchEnabled
        ))
      case request: DeleteRequest =>
        val handler = new RegularRequestHandler[DeleteDocumentOperationBlockContext, DeleteDocumentOperation](
          engine, task, actionType, request, listener, chain, channel, threadPool
        )
        handler.handle(new DeleteDocumentOperationEsRequestContext(
          channel, task.getId, actionType, DeleteDocumentOperation.from(request), request, clusterService, threadPool, crossClusterSearchEnabled
        ))
      case request: IndicesAliasesRequest =>
        val handler = new RegularRequestHandler[IndicesAliasesOperationBlockContext, IndicesAliasesOperation](
          engine, task, actionType, request, listener, chain, channel, threadPool
        )
        handler.handle(new IndicesAliasesOperationEsRequestContext(
          channel, task.getId, actionType, IndicesAliasesOperation.from(request), request, clusterService, threadPool, crossClusterSearchEnabled
        ))
      case request: GetSettingsRequest =>
        val handler = new RegularRequestHandler[GetSettingsOperationBlockContext, GetSettingsOperation](
          engine, task, actionType, request, listener, chain, channel, threadPool
        )
        handler.handle(new GetSettingsOperationEsRequestContext(
          channel, task.getId, actionType, GetSettingsOperation.from(request), request, clusterService, threadPool, crossClusterSearchEnabled
        ))
      case request: IndicesStatsRequest =>
        val handler = new RegularRequestHandler[IndicesStatsOperationBlockContext, IndicesStatsOperation](
          engine, task, actionType, request, listener, chain, channel, threadPool
        )
        handler.handle(new IndicesStatsOperationEsRequestContext(
          channel, task.getId, actionType, IndicesStatsOperation.from(request), request, clusterService, threadPool, crossClusterSearchEnabled
        ))
      case request: IndicesShardStoresRequest =>
        val handler = new RegularRequestHandler[IndicesShardStoresOperationBlockContext, IndicesShardStoresOperation](
          engine, task, actionType, request, listener, chain, channel, threadPool
        )
        handler.handle(new IndicesShardStoresOperationEsRequestContext(
          channel, task.getId, actionType, IndicesShardStoresOperation.from(request), request, clusterService, threadPool, crossClusterSearchEnabled
        ))
      // Particular case because bug: https://github.com/elastic/elasticsearch/issues/28671
      case request: RestoreSnapshotRequest =>
        val handler = new RegularRequestHandler[RestoreSnapshotOperationBlockContext, RestoreSnapshotOperation](
          engine, task, actionType, request, listener, chain, channel, threadPool
        )
        handler.handle(new RestoreSnapshotOperationEsRequestContext(
          channel, task.getId, actionType, RestoreSnapshotOperation.from(request), request, clusterService, threadPool, crossClusterSearchEnabled
        ))
      case request: IndicesRequest.Replaceable =>
        val handler = new RegularRequestHandler[IndicesReplaceableOperationBlockContext, IndicesReplaceableOperation](
          engine, task, actionType, request, listener, chain, channel, threadPool
        )
        handler.handle(new IndicesReplaceableOperationEsRequestContext(
          channel, task.getId, actionType, IndicesReplaceableOperation.from(request), request, clusterService, threadPool, crossClusterSearchEnabled
        ))
      case request: ReindexRequest =>
        val handler = new RegularRequestHandler[ReindexOperationBlockContext, ReindexOperation](
          engine, task, actionType, request, listener, chain, channel, threadPool
        )
        handler.handle(new ReindexOperationEsRequestContext(
          channel, task.getId, actionType, ReindexOperation.from(request), request, clusterService, threadPool, crossClusterSearchEnabled
        ))
      // todo: sth here
      case request =>
        handleSearchTemplateRequest(engine, channel, task, actionType, request, listener, chain, crossClusterSearchEnabled) orElse
          handleReflectionBasedIndicesRequest(engine, channel, task, actionType, request, listener, chain, crossClusterSearchEnabled) getOrElse
          handleGeneralNonIndexOperation(engine, channel, task, actionType, request, listener, chain, crossClusterSearchEnabled)
    }

  private def handleSearchTemplateRequest(engine: Engine,
                                          channel: RestChannel,
                                          task: EsTask,
                                          actionType: String,
                                          request: ActionRequest,
                                          listener: ActionListener[ActionResponse],
                                          chain: ActionFilterChain[ActionRequest, ActionResponse],
                                          crossClusterSearchEnabled: Boolean) = {
    SearchTemplateOperation
      .from(request)
      .map { operation =>
        val handler = new RegularRequestHandler[SearchTemplateOperationBlockContext, SearchTemplateOperation](
          engine, task, actionType, request, listener, chain, channel, threadPool
        )
        handler.handle(new SearchTemplateOperationEsRequestContext(
          channel, task.getId, actionType, operation, request, clusterService, threadPool, crossClusterSearchEnabled
        ))
      }
  }

  private def handleReflectionBasedIndicesRequest(engine: Engine,
                                                  channel: RestChannel,
                                                  task: EsTask,
                                                  actionType: String,
                                                  request: ActionRequest,
                                                  listener: ActionListener[ActionResponse],
                                                  chain: ActionFilterChain[ActionRequest, ActionResponse],
                                                  crossClusterSearchEnabled: Boolean) = {
    ReflectionBasedIndicesOperation
      .from(request)
      .map { operation =>
        val handler = new RegularRequestHandler[ReflectionBasedIndicesOperationBlockContext, ReflectionBasedIndicesOperation](
          engine, task, actionType, request, listener, chain, channel, threadPool
        )
        handler.handle(new ReflectionBasedIndicesOperationEsRequestContext(
          channel, task.getId, actionType, operation, request, clusterService, threadPool, crossClusterSearchEnabled
        ))
      }
  }

  private def handleGeneralNonIndexOperation(engine: Engine,
                                             channel: RestChannel,
                                             task: EsTask,
                                             actionType: String,
                                             request: ActionRequest,
                                             listener: ActionListener[ActionResponse],
                                             chain: ActionFilterChain[ActionRequest, ActionResponse],
                                             crossClusterSearchEnabled: Boolean) = {
    val handler = new RegularRequestHandler[GeneralNonIndexOperationBlockContext, GeneralNonIndexOperation](
      engine, task, actionType, request, listener, chain, channel, threadPool
    )
    handler.handle(new GeneralNonIndexOperationEsRequestContext(
      channel, task.getId, actionType, new GeneralNonIndexOperation(request), request, clusterService, threadPool, crossClusterSearchEnabled
    ))
  }
}

final case class RequestSeemsToBeInvalid[T: ClassTag]()
  extends IllegalStateException(s"Request '${implicitly[ClassTag[T]].getClass.getSimpleName}' cannot be handled")
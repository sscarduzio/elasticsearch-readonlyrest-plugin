package tech.beshu.ror.es.request

import monix.eval.Task
import monix.execution.Scheduler
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action._
import org.elasticsearch.action.admin.cluster.snapshots.create.CreateSnapshotRequest
import org.elasticsearch.action.admin.cluster.snapshots.get.GetSnapshotsRequest
import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotRequest
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsRequest
import org.elasticsearch.action.admin.indices.shards.IndicesShardStoresRequest
import org.elasticsearch.action.admin.indices.stats.IndicesStatsRequest
import org.elasticsearch.action.admin.indices.template.put.PutIndexTemplateRequest
import org.elasticsearch.action.bulk.{BulkRequest, BulkShardRequest}
import org.elasticsearch.action.delete.DeleteRequest
import org.elasticsearch.action.get.MultiGetRequest
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.action.search.{MultiSearchRequest, SearchRequest}
import org.elasticsearch.action.support.ActionFilterChain
import org.elasticsearch.action.termvectors.MultiTermVectorsRequest
import org.elasticsearch.index.reindex.ReindexRequest
import org.elasticsearch.rest.RestChannel
import org.elasticsearch.tasks.{Task => EsTask}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.boot.Engine
import tech.beshu.ror.es.RorClusterService
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.context.types._
import tech.beshu.ror.es.request.handler.regular.RegularRequestHandler
import tech.beshu.ror.es.request.handler.usermetadata.CurrentUserMetadataRequestHandler
import tech.beshu.ror.es.rradmin.RRAdminRequest

import scala.language.postfixOps
import scala.reflect.ClassTag

class AclAwareRequestFilter(clusterService: RorClusterService,
                            threadPool: ThreadPool)
                           (implicit scheduler: Scheduler)
  extends Logging {

  def handle(engine: Engine,
             esContext: EsContext): Task[Unit] = {
    esContext.actionRequest match {
      case request: RRAdminRequest =>
        val handler = new CurrentUserMetadataRequestHandler(engine, esContext, threadPool)
        handler.handle(new CurrentUserMetadataEsRequestContext(request, esContext, clusterService, threadPool))
      case _ =>
        val regularRequestHandler = new RegularRequestHandler(engine, esContext, threadPool)
        handleEsRestApiRequest(regularRequestHandler, esContext)
    }
  }

  private def handleEsRestApiRequest(regularRequestHandler: RegularRequestHandler, esContext: EsContext) = {
    esContext.actionRequest match {
      case request: GetSnapshotsRequest =>
        regularRequestHandler.handle(new GetSnapshotsEsRequestContext(request, esContext, clusterService, threadPool))
      case request: CreateSnapshotRequest =>
        regularRequestHandler.handle(new CreateSnapshotEsRequestContext(request, esContext, clusterService, threadPool))
      case request: PutIndexTemplateRequest =>
        regularRequestHandler.handle(new CreateTemplateEsRequestContext(request, esContext, clusterService, threadPool))
      // todo: more template requests
      case request: BulkShardRequest =>
        regularRequestHandler.handle(new BulkShardEsRequestContext(request, esContext, clusterService, threadPool))
      case request: IndexRequest =>
        regularRequestHandler.handle(new IndexEsRequestContext(request, esContext, clusterService, threadPool))
      case request: MultiGetRequest =>
        regularRequestHandler.handle(new MultiGetEsRequestContext(request, esContext, clusterService, threadPool))
      case request: SearchRequest =>
        regularRequestHandler.handle(new SearchEsRequestContext(request, esContext, clusterService, threadPool))
      case request: MultiSearchRequest =>
        regularRequestHandler.handle(new MultiSearchEsRequestContext(request, esContext, clusterService, threadPool))
      case request: MultiTermVectorsRequest =>
        regularRequestHandler.handle(new MultiTermVectorsEsRequestContext(request, esContext, clusterService, threadPool))
      case request: BulkRequest =>
        regularRequestHandler.handle(new BulkEsRequestContext(request, esContext, clusterService, threadPool))
      case request: DeleteRequest =>
        regularRequestHandler.handle(new DeleteDocumentEsRequestContext(request, esContext, clusterService, threadPool))
      case request: IndicesAliasesRequest =>
        regularRequestHandler.handle(new IndicesAliasesEsRequestContext(request, esContext, clusterService, threadPool))
      case request: GetSettingsRequest =>
        regularRequestHandler.handle(new GetSettingsEsRequestContext(request, esContext, clusterService, threadPool))
      case request: IndicesStatsRequest =>
        regularRequestHandler.handle(new IndicesStatsEsRequestContext(request, esContext, clusterService, threadPool))
      case request: IndicesShardStoresRequest =>
        regularRequestHandler.handle(new IndicesShardStoresEsRequestContext(request, esContext, clusterService, threadPool))
      case request: RestoreSnapshotRequest =>
        // Particular case because bug: https://github.com/elastic/elasticsearch/issues/28671
        regularRequestHandler.handle(new RestoreSnapshotEsRequestContext(request, esContext, clusterService, threadPool))
      case request: IndicesRequest.Replaceable =>
        regularRequestHandler.handle(new IndicesReplaceableEsRequestContext(request, esContext, clusterService, threadPool))
      case request: ReindexRequest =>
        regularRequestHandler.handle(new ReindexEsRequestContext(request, esContext, clusterService, threadPool))
      case request: CompositeIndicesRequest =>
        SqlIndicesEsRequestContext.from(request, esContext, clusterService, threadPool) match {
          case Some(sqlRequest) =>
            regularRequestHandler.handle(sqlRequest)
          case None =>
            logger.error(s"Found an instance of CompositeIndicesRequest that could not be handled: report this as a bug immediately! ${request.getClass.getSimpleName}")
            regularRequestHandler.handle(new DummyCompositeIndicesEsRequestContext(request, esContext, clusterService, threadPool))
        }
      // todo: sth here
      case _ =>
        handleSearchTemplateRequest(regularRequestHandler, esContext) orElse
          handleReflectionBasedIndicesRequest(regularRequestHandler, esContext) getOrElse
          handleGeneralNonIndexOperation(regularRequestHandler, esContext)
    }
  }

  private def handleSearchTemplateRequest(regularRequestHandler: RegularRequestHandler, esContext: EsContext) = {
    SearchTemplateEsRequestContext
      .from(esContext.actionRequest, esContext, clusterService, threadPool)
      .map(regularRequestHandler.handle(_))
  }

  private def handleReflectionBasedIndicesRequest(regularRequestHandler: RegularRequestHandler, esContext: EsContext) = {
    ReflectionBasedIndicesEsRequestContext
      .from(esContext.actionRequest, esContext, clusterService, threadPool)
      .map(regularRequestHandler.handle(_))
  }

  private def handleGeneralNonIndexOperation(regularRequestHandler: RegularRequestHandler, esContext: EsContext) = {
    regularRequestHandler.handle {
      new GeneralNonIndexEsRequestContext(esContext.actionRequest, esContext, clusterService, threadPool)
    }
  }
}

object AclAwareRequestFilter {
  final case class EsContext(channel: RestChannel,
                             task: EsTask,
                             actionType: String,
                             actionRequest: ActionRequest,
                             listener: ActionListener[ActionResponse],
                             chain: ActionFilterChain[ActionRequest, ActionResponse],
                             crossClusterSearchEnabled: Boolean,
                             involveFilters: Boolean)
}

final case class RequestSeemsToBeInvalid[T: ClassTag](message: String)
  extends IllegalStateException(s"Request '${implicitly[ClassTag[T]].getClass.getSimpleName}' cannot be handled; [msg: $message]")
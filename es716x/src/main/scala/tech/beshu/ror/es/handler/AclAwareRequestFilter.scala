/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.es.handler

import cats.implicits._
import monix.eval.Task
import monix.execution.Scheduler
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action._
import org.elasticsearch.action.admin.cluster.allocation.ClusterAllocationExplainRequest
import org.elasticsearch.action.admin.cluster.repositories.cleanup.CleanupRepositoryRequest
import org.elasticsearch.action.admin.cluster.repositories.delete.DeleteRepositoryRequest
import org.elasticsearch.action.admin.cluster.repositories.get.GetRepositoriesRequest
import org.elasticsearch.action.admin.cluster.repositories.put.PutRepositoryRequest
import org.elasticsearch.action.admin.cluster.repositories.verify.VerifyRepositoryRequest
import org.elasticsearch.action.admin.cluster.reroute.ClusterRerouteRequest
import org.elasticsearch.action.admin.cluster.snapshots.create.CreateSnapshotRequest
import org.elasticsearch.action.admin.cluster.snapshots.delete.DeleteSnapshotRequest
import org.elasticsearch.action.admin.cluster.snapshots.get.GetSnapshotsRequest
import org.elasticsearch.action.admin.cluster.snapshots.restore.RestoreSnapshotRequest
import org.elasticsearch.action.admin.cluster.snapshots.status.SnapshotsStatusRequest
import org.elasticsearch.action.admin.cluster.state.ClusterStateRequest
import org.elasticsearch.action.admin.indices.alias.IndicesAliasesRequest
import org.elasticsearch.action.admin.indices.alias.get.GetAliasesRequest
import org.elasticsearch.action.admin.indices.get.GetIndexRequest
import org.elasticsearch.action.admin.indices.resolve.ResolveIndexAction
import org.elasticsearch.action.admin.indices.rollover.RolloverRequest
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsRequest
import org.elasticsearch.action.admin.indices.shards.IndicesShardStoresRequest
import org.elasticsearch.action.admin.indices.shrink.ResizeRequest
import org.elasticsearch.action.admin.indices.stats.IndicesStatsRequest
import org.elasticsearch.action.admin.indices.template.delete.{DeleteComponentTemplateAction, DeleteComposableIndexTemplateAction, DeleteIndexTemplateRequest}
import org.elasticsearch.action.admin.indices.template.get.{GetComponentTemplateAction, GetComposableIndexTemplateAction, GetIndexTemplatesRequest}
import org.elasticsearch.action.admin.indices.template.post.{SimulateIndexTemplateRequest, SimulateTemplateAction}
import org.elasticsearch.action.admin.indices.template.put.{PutComponentTemplateAction, PutComposableIndexTemplateAction, PutIndexTemplateRequest}
import org.elasticsearch.action.bulk.{BulkRequest, BulkShardRequest}
import org.elasticsearch.action.delete.DeleteRequest
import org.elasticsearch.action.datastreams.ModifyDataStreamsAction
import org.elasticsearch.action.get.{GetRequest, MultiGetRequest}
import org.elasticsearch.action.index.IndexRequest
import org.elasticsearch.action.search.{MultiSearchRequest, SearchRequest}
import org.elasticsearch.action.support.ActionFilterChain
import org.elasticsearch.action.termvectors.MultiTermVectorsRequest
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.index.reindex.ReindexRequest
import org.elasticsearch.rest.RestChannel
import org.elasticsearch.tasks.{Task => EsTask}
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.es.handler.request.context.types.datastreams.{ModifyDataStreamsEsRequestContext, ReflectionBasedDataStreamsEsRequestContext}
import tech.beshu.ror.accesscontrol.AccessControl.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.{Action, Header}
import tech.beshu.ror.accesscontrol.matchers.UniqueIdentifierGenerator
import tech.beshu.ror.boot.ReadonlyRest.Engine
import tech.beshu.ror.boot.engines.Engines
import tech.beshu.ror.es.actions.RorActionRequest
import tech.beshu.ror.es.actions.rrauditevent.RRAuditEventRequest
import tech.beshu.ror.es.actions.rrmetadata.RRUserMetadataRequest
import tech.beshu.ror.es.handler.AclAwareRequestFilter._
import tech.beshu.ror.es.handler.request.ActionRequestOps._
import tech.beshu.ror.es.handler.request.RestRequestOps._
import tech.beshu.ror.es.handler.request.context.types._
import tech.beshu.ror.es.{ResponseFieldsFiltering, RorClusterService}

import java.time.Instant
import scala.reflect.ClassTag

class AclAwareRequestFilter(clusterService: RorClusterService,
                            settings: Settings,
                            threadPool: ThreadPool)
                           (implicit generator: UniqueIdentifierGenerator,
                            scheduler: Scheduler)
  extends Logging {

  def handle(engines: Engines,
             esContext: EsContext): Task[Either[Error, Unit]] = {
    esContext
      .pickEngineToHandle(engines)
      .map(handleRequestWithEngine(_, esContext))
      .sequence
  }

  private def handleRequestWithEngine(engine: Engine,
                                      esContext: EsContext) = {
    esContext.actionRequest match {
      case request: RRUserMetadataRequest =>
        val handler = new CurrentUserMetadataRequestHandler(engine, esContext)
        handler.handle(new CurrentUserMetadataEsRequestContext(request, esContext, clusterService, threadPool))
      case _ =>
        val regularRequestHandler = new RegularRequestHandler(engine, esContext, threadPool)
        handleEsRestApiRequest(regularRequestHandler, esContext, engine.core.accessControl.staticContext)
    }
  }

  private def handleEsRestApiRequest(regularRequestHandler: RegularRequestHandler,
                                     esContext: EsContext,
                                     aclContext: AccessControlStaticContext) = {
    esContext.actionRequest match {
      case request: RRAuditEventRequest =>
        regularRequestHandler.handle(new AuditEventESRequestContext(request, esContext, clusterService, threadPool))
      case request: RorActionRequest =>
        regularRequestHandler.handle(new RorApiEsRequestContext(request, esContext, clusterService, threadPool))
      // snapshots
      case request: GetSnapshotsRequest =>
        regularRequestHandler.handle(new GetSnapshotsEsRequestContext(request, esContext, clusterService, threadPool))
      case request: CreateSnapshotRequest =>
        regularRequestHandler.handle(new CreateSnapshotEsRequestContext(request, esContext, clusterService, threadPool))
      case request: DeleteSnapshotRequest =>
        regularRequestHandler.handle(new DeleteSnapshotEsRequestContext(request, esContext, clusterService, threadPool))
      case request: RestoreSnapshotRequest =>
        regularRequestHandler.handle(new RestoreSnapshotEsRequestContext(request, esContext, clusterService, threadPool))
      case request: SnapshotsStatusRequest =>
        regularRequestHandler.handle(new SnapshotsStatusEsRequestContext(request, esContext, clusterService, threadPool))
      // repositories
      case request: GetRepositoriesRequest =>
        regularRequestHandler.handle(new GetRepositoriesEsRequestContext(request, esContext, clusterService, threadPool))
      case request: PutRepositoryRequest =>
        regularRequestHandler.handle(new CreateRepositoryEsRequestContext(request, esContext, clusterService, threadPool))
      case request: DeleteRepositoryRequest =>
        regularRequestHandler.handle(new DeleteRepositoryEsRequestContext(request, esContext, clusterService, threadPool))
      case request: VerifyRepositoryRequest =>
        regularRequestHandler.handle(new VerifyRepositoryEsRequestContext(request, esContext, clusterService, threadPool))
      case request: CleanupRepositoryRequest =>
        regularRequestHandler.handle(new CleanupRepositoryEsRequestContext(request, esContext, clusterService, threadPool))
      // templates
      case request: GetIndexTemplatesRequest =>
        regularRequestHandler.handle(new GetTemplatesEsRequestContext(request, esContext, clusterService, threadPool))
      case request: PutIndexTemplateRequest =>
        regularRequestHandler.handle(new PutTemplateEsRequestContext(request, esContext, clusterService, threadPool))
      case request: DeleteIndexTemplateRequest =>
        regularRequestHandler.handle(new DeleteTemplateEsRequestContext(request, esContext, clusterService, threadPool))
      case request: GetComposableIndexTemplateAction.Request =>
        regularRequestHandler.handle(new GetComposableIndexTemplateEsRequestContext(request, esContext, clusterService, threadPool))
      case request: PutComposableIndexTemplateAction.Request =>
        regularRequestHandler.handle(new PutComposableIndexTemplateEsRequestContext(request, esContext, clusterService, threadPool))
      case request: DeleteComposableIndexTemplateAction.Request =>
        regularRequestHandler.handle(new DeleteComposableIndexTemplateEsRequestContext(request, esContext, clusterService, threadPool))
      case request: GetComponentTemplateAction.Request =>
        regularRequestHandler.handle(new GetComponentTemplateEsRequestContext(request, esContext, clusterService, threadPool))
      case request: PutComponentTemplateAction.Request =>
        regularRequestHandler.handle(new PutComponentTemplateEsRequestContext(request, esContext, clusterService, threadPool))
      case request: DeleteComponentTemplateAction.Request =>
        regularRequestHandler.handle(new DeleteComponentTemplateEsRequestContext(request, esContext, clusterService, threadPool))
      case request: SimulateIndexTemplateRequest =>
        regularRequestHandler.handle(new SimulateIndexTemplateRequestEsRequestContext(request, esContext, aclContext, clusterService, threadPool))
      case request: SimulateTemplateAction.Request =>
        regularRequestHandler.handle(SimulateTemplateRequestEsRequestContext.from(request, esContext, clusterService, threadPool))
      // aliases
      case request: GetAliasesRequest =>
        regularRequestHandler.handle(new GetAliasesEsRequestContext(request, esContext, aclContext, clusterService, threadPool))
      case request: IndicesAliasesRequest =>
        regularRequestHandler.handle(new IndicesAliasesEsRequestContext(request, esContext, aclContext, clusterService, threadPool))
      // data streams
      case request: ModifyDataStreamsAction.Request =>
        regularRequestHandler.handle(new ModifyDataStreamsEsRequestContext(request, esContext, clusterService, threadPool))
      // indices
      case request: GetIndexRequest =>
        regularRequestHandler.handle(new GetIndexEsRequestContext(request, esContext, aclContext, clusterService, threadPool))
      case request: BulkShardRequest =>
        regularRequestHandler.handle(new BulkShardEsRequestContext(request, esContext, aclContext, clusterService, threadPool))
      case request: IndexRequest =>
        regularRequestHandler.handle(new IndexEsRequestContext(request, esContext, aclContext, clusterService, threadPool))
      case request: MultiGetRequest =>
        regularRequestHandler.handle(new MultiGetEsRequestContext(request, esContext, clusterService, threadPool))
      case request: SearchRequest =>
        regularRequestHandler.handle(new SearchEsRequestContext(request, esContext, aclContext, clusterService, threadPool))
      case request: GetRequest =>
        regularRequestHandler.handle(new GetEsRequestContext(request, esContext, aclContext, clusterService, threadPool))
      case request: MultiSearchRequest =>
        regularRequestHandler.handle(new MultiSearchEsRequestContext(request, esContext, clusterService, threadPool))
      case request: MultiTermVectorsRequest =>
        regularRequestHandler.handle(new MultiTermVectorsEsRequestContext(request, esContext, aclContext, clusterService, threadPool))
      case request: BulkRequest =>
        regularRequestHandler.handle(new BulkEsRequestContext(request, esContext, clusterService, threadPool))
      case request: DeleteRequest =>
        regularRequestHandler.handle(new DeleteDocumentEsRequestContext(request, esContext, aclContext, clusterService, threadPool))
      case request: GetSettingsRequest =>
        regularRequestHandler.handle(new GetSettingsEsRequestContext(request, esContext, aclContext, clusterService, threadPool))
      case request: IndicesStatsRequest =>
        regularRequestHandler.handle(new IndicesStatsEsRequestContext(request, esContext, aclContext, clusterService, threadPool))
      case request: IndicesShardStoresRequest =>
        regularRequestHandler.handle(new IndicesShardStoresEsRequestContext(request, esContext, aclContext, clusterService, threadPool))
      case request: ClusterStateRequest =>
        TemplateClusterStateEsRequestContext.from(request, esContext, clusterService, settings, threadPool) match {
          case Some(requestContext) =>
            regularRequestHandler.handle(requestContext)
          case None =>
            regularRequestHandler.handle(new ClusterStateEsRequestContext(request, esContext, aclContext, clusterService, threadPool))
        }
      case request: ClusterAllocationExplainRequest =>
        regularRequestHandler.handle(new ClusterAllocationExplainEsRequestContext(request, esContext, aclContext, clusterService, threadPool))
      case request: RolloverRequest =>
        regularRequestHandler.handle(new RolloverEsRequestContext(request, esContext, aclContext, clusterService, threadPool))
      case request: ResolveIndexAction.Request =>
        regularRequestHandler.handle(new ResolveIndexEsRequestContext(request, esContext, aclContext, clusterService, threadPool))
      case request: IndicesRequest.Replaceable if request.notDataStreamRelated =>
        regularRequestHandler.handle(new IndicesReplaceableEsRequestContext(request, esContext, aclContext, clusterService, threadPool))
      case request: ReindexRequest =>
        regularRequestHandler.handle(new ReindexEsRequestContext(request, esContext, aclContext, clusterService, threadPool))
      case request: ResizeRequest =>
        regularRequestHandler.handle(new ResizeEsRequestContext(request, esContext, aclContext, clusterService, threadPool))
      case request: ClusterRerouteRequest =>
        regularRequestHandler.handle(new ClusterRerouteEsRequestContext(request, esContext, aclContext, clusterService, threadPool))
      case request: CompositeIndicesRequest =>
        ReflectionBasedActionRequest(esContext, aclContext, clusterService, threadPool) match {
          case SqlIndicesEsRequestContext(r) => regularRequestHandler.handle(r)
          case SearchTemplateEsRequestContext(r) => regularRequestHandler.handle(r)
          case MultiSearchTemplateEsRequestContext(r) => regularRequestHandler.handle(r)
          case _ =>
            logger.error(s"Found an child request of CompositeIndicesRequest that could not be handled: report this as a bug immediately! ${request.getClass.getSimpleName}")
            regularRequestHandler.handle(new DummyCompositeIndicesEsRequestContext(request, esContext, aclContext, clusterService, threadPool))
        }
      // rest
      case _ =>
        ReflectionBasedActionRequest(esContext, aclContext, clusterService, threadPool) match {
          case XpackAsyncSearchRequestContext(request) => regularRequestHandler.handle(request)
          // rollup
          case PutRollupJobEsRequestContext(request) => regularRequestHandler.handle(request)
          case GetRollupCapsEsRequestContext(request) => regularRequestHandler.handle(request)
          // data streams
          case ReflectionBasedDataStreamsEsRequestContext(request) => regularRequestHandler.handle(request)
          // indices based
          case ReflectionBasedIndicesEsRequestContext(request) => regularRequestHandler.handle(request)
          // rest
          case _ =>
            regularRequestHandler.handle {
              new GeneralNonIndexEsRequestContext(esContext.actionRequest, esContext, clusterService, threadPool)
            }
        }
    }
  }

}

object AclAwareRequestFilter {
  final case class EsContext(channel: RestChannel with ResponseFieldsFiltering,
                             nodeName: String,
                             task: EsTask,
                             action: Action,
                             actionRequest: ActionRequest,
                             listener: ActionListener[ActionResponse],
                             chain: EsChain,
                             threadContextResponseHeaders: Set[(String, String)]) {
    lazy val requestContextId = s"${channel.request().hashCode()}-${actionRequest.hashCode()}#${task.getId}"
    val timestamp: Instant = Instant.now()

    def pickEngineToHandle(engines: Engines): Either[Error, Engine] = {
      val impersonationHeaderPresent = isImpersonationHeader
      engines.impersonatorsEngine match {
        case Some(impersonatorsEngine) if impersonationHeaderPresent => Right(impersonatorsEngine)
        case None if impersonationHeaderPresent => Left(Error.ImpersonatorsEngineNotConfigured)
        case Some(_) | None => Right(engines.mainEngine)
      }
    }

    private def isImpersonationHeader = {
      channel
        .request()
        .allHeaders()
        .exists { case Header(name, _) => name === Header.Name.impersonateAs }
    }
  }

  final class EsChain(chain: ActionFilterChain[ActionRequest, ActionResponse]) {

    def continue(esContext: EsContext,
                 listener: ActionListener[ActionResponse]): Unit = {
      continue(esContext.task, esContext.action, esContext.actionRequest, listener)
    }

    def continue(task: EsTask,
                 action: Action,
                 request: ActionRequest,
                 listener: ActionListener[ActionResponse]): Unit = {
      chain.proceed(task, action.value, request, listener)
    }
  }

  sealed trait Error
  object Error {
    case object ImpersonatorsEngineNotConfigured extends Error
  }
}

final case class RequestSeemsToBeInvalid[T: ClassTag](message: String, cause: Throwable = null)
  extends IllegalStateException(s"Request '${implicitly[ClassTag[T]].runtimeClass.getSimpleName}' cannot be handled; [msg: $message]", cause)
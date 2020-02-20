/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es

import java.util
import java.util.function.{Supplier, UnaryOperator}

import better.files.File
import cats.data.EitherT
import com.google.common.collect.Maps
import monix.eval.Task
import monix.execution.Scheduler
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action._
import org.elasticsearch.action.support.{ActionFilter, ActionFilters, TransportAction}
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver
import org.elasticsearch.cluster.node.DiscoveryNodes
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.common.settings.{ClusterSettings, IndexScopedSettings, Settings, SettingsFilter}
import org.elasticsearch.common.util.concurrent.ThreadContext
import org.elasticsearch.common.util.set.Sets
import org.elasticsearch.common.xcontent.{DeprecationHandler, NamedXContentRegistry, StatusToXContentObject, ToXContent, ToXContentObject, XContentFactory, XContentType}
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService
import org.elasticsearch.plugins.ActionPlugin
import org.elasticsearch.plugins.ActionPlugin.ActionHandler
import org.elasticsearch.rest.{BytesRestResponse, RestChannel, RestController, RestHandler, RestRequest, RestResponse, RestStatus}
import org.elasticsearch.tasks
import org.elasticsearch.tasks.TaskManager
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.usage.UsageService
import tech.beshu.ror.boot.StartingFailure
import tech.beshu.ror.es.utils.ThreadRepo
import tech.beshu.ror.proxy.es.EsActionRequestHandler.HandlingResult
import tech.beshu.ror.proxy.es.EsRestServiceSimulator.ProcessingResult
import tech.beshu.ror.proxy.es.clients.{EsRestNodeClient, RestHighLevelClientAdapter}
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.TaskOps._

import scala.collection.JavaConverters._
import scala.util.{Failure, Success, Try}

class EsRestServiceSimulator(simulatorEsSettings: File,
                             proxyFilter: ProxyIndexLevelActionFilter,
                             esClient: RestHighLevelClientAdapter,
                             esActionRequestHandler: EsActionRequestHandler,
                             threadPool: ThreadPool)
                            (implicit scheduler: Scheduler)
  extends Logging {

  private val actionModule: ActionModule = configureSimulator()

  def processRequest(request: RestRequest): Task[ProcessingResult] = {
    val restChannel = new ProxyRestChannel(request)
    val threadContext = threadPool.getThreadContext
    val dispatchResult = Try {
      threadContext.stashContext.bracket { _ =>
        ProxyThreadRepo.setRestChannel(restChannel)
        actionModule.getRestController
          .dispatchRequest(request, restChannel, threadContext)
      }
    }
    dispatchResult match {
      case Success(_) =>
        restChannel
          .result
          .andThen { case _ =>
            ProxyThreadRepo.clearRestChannel()
          }
      case Failure(exception) =>
        Task.now(ProcessingResult.Response(restChannel.failureResponseFrom(exception)))
    }
  }

  def stop(): Task[Unit] = proxyFilter.stop()

  private def configureSimulator() = {
    val settings = Settings.fromXContent(
      XContentFactory
        .xContent(XContentType.JSON)
        .createParser(
          NamedXContentRegistry.EMPTY,
          DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
          simulatorEsSettings.contentAsString
        )
    )
    createActionModule(settings)
  }

  private def createActionModule(settings: Settings) = {
    val nodeClient = new EsRestNodeClient(new NodeClient(settings, threadPool), proxyFilter, esClient, settings, threadPool)
    val clusterSettings = new ClusterSettings(settings, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS, Sets.newHashSet())
    val actionModule = new ActionModule(
      false,
      settings,
      new IndexNameExpressionResolver(),
      IndexScopedSettings.DEFAULT_SCOPED_SETTINGS,
      clusterSettings,
      new SettingsFilter(List.empty.asJava),
      threadPool,
      List[ActionPlugin](new RORActionPlugin()).asJava,
      nodeClient,
      new NoneCircuitBreakerService(),
      new UsageService(),
      new ClusterService(settings, clusterSettings, threadPool)
    )

    nodeClient.initialize(
      actions(
        actionModule,
        new ActionFilters(Set[ActionFilter](proxyFilter).asJava),
        new TaskManager(settings, threadPool, Set.empty[String].asJava)
      ),
      () => "ROR_proxy",
      null
    )

    actionModule.initRestHandlers(() => DiscoveryNodes.EMPTY_NODES)
    actionModule
  }

  private def actions(actionModule: ActionModule,
                      afs: ActionFilters,
                      tm: TaskManager): util.Map[ActionType[_ <: ActionResponse], TransportAction[_ <: ActionRequest, _ <: ActionResponse]] = {
    val map: util.HashMap[ActionType[_ <: ActionResponse], TransportAction[_ <: ActionRequest, _ <: ActionResponse]] = Maps.newHashMap()
    actionModule
      .getActions.asScala
      .foreach { case (_, action) =>
        map.put(
          actionTypeOf(action),
          transportAction[ActionRequest, ActionResponse](action.getAction.name(), afs, tm)
        )
      }
    map
  }

  private def actionTypeOf(actionHandler: ActionHandler[_ <: ActionRequest, _ <: ActionResponse]): ActionType[_ <: ActionResponse] = {
    actionHandler.getAction
  }

  private def transportAction[R <: ActionRequest, RR <: ActionResponse](actionName: String,
                                                                        actionFilters: ActionFilters,
                                                                        taskManager: TaskManager): TransportAction[_ <: ActionRequest, _ <: ActionResponse] =
    new TransportAction[R, RR](actionName, actionFilters, taskManager) {
      override def doExecute(task: tasks.Task, request: R, listener: ActionListener[RR]): Unit = {
        ProxyThreadRepo.getRestChannel match {
          case Some(proxyRestChannel) =>
            esActionRequestHandler
              .handle(request)
              .runAsyncF {
                case Right(HandlingResult.Handled(response: StatusToXContentObject)) =>
                  sendResponseThroughChannel(proxyRestChannel, response)
                case Right(HandlingResult.Handled(response)) =>
                  sendResponseThroughChannel(proxyRestChannel, response)
                case Right(HandlingResult.PassItThrough) => proxyRestChannel.passThrough()
                case Left(ex) => proxyRestChannel.sendFailureResponse(ex)
              }
          case None =>
            EsRestServiceSimulator.this.logger.warn(s"Request $request won't be executed")
        }
      }
    }

  private def sendResponseThroughChannel(proxyRestChannel: ProxyRestChannel,
                                         response: ToXContentObject): Unit = {
    proxyRestChannel.sendResponse(new BytesRestResponse(
      RestStatus.OK,
      response.toXContent(proxyRestChannel.newBuilder(), ToXContent.EMPTY_PARAMS)
    ))
  }

  private def sendResponseThroughChannel(proxyRestChannel: ProxyRestChannel,
                                         response: StatusToXContentObject): Unit = {
    proxyRestChannel.sendResponse(new BytesRestResponse(
      response.status(),
      response.toXContent(proxyRestChannel.newBuilder(), ToXContent.EMPTY_PARAMS)
    ))
  }

  private class RORActionPlugin extends ActionPlugin {

    override def getRestHandlers(settings: Settings,
                                 restController: RestController,
                                 clusterSettings: ClusterSettings,
                                 indexScopedSettings: IndexScopedSettings,
                                 settingsFilter: SettingsFilter,
                                 indexNameExpressionResolver: IndexNameExpressionResolver,
                                 nodesInCluster: Supplier[DiscoveryNodes]): util.List[RestHandler] = {
      super.getRestHandlers(settings, restController, clusterSettings, indexScopedSettings, settingsFilter, indexNameExpressionResolver, nodesInCluster)
    }

    override def getRestHandlerWrapper(threadContext: ThreadContext): UnaryOperator[RestHandler] = {
      restHandler: RestHandler =>
        (request: RestRequest, channel: RestChannel, client: NodeClient) => {
          ThreadRepo.setRestChannel(channel)
          restHandler.handleRequest(request, channel, client)
        }
    }
  }
}

object EsRestServiceSimulator {

  sealed trait ProcessingResult
  object ProcessingResult {
    final case class Response(restResponse: RestResponse) extends ProcessingResult
    case object PassThrough extends ProcessingResult
  }

  def create(esClient: RestHighLevelClientAdapter,
             threadPool: ThreadPool)
            (implicit scheduler: Scheduler): Task[Either[StartingFailure, EsRestServiceSimulator]] = {
    val simulatorEsSettingsFile = File(getClass.getClassLoader.getResource("elasticsearch.yml"))
    val simulatorEsSettingsFolder = simulatorEsSettingsFile.parent.path
    val esActionRequestHandler = new EsActionRequestHandler(esClient)
    val result = for {
      filter <- EitherT(ProxyIndexLevelActionFilter.create(simulatorEsSettingsFolder, esClient, threadPool))
    } yield new EsRestServiceSimulator(simulatorEsSettingsFile, filter, esClient, esActionRequestHandler, threadPool)
    result.value
  }
}

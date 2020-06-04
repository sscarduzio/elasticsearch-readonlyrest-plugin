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
import org.elasticsearch.cluster.ClusterState
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver
import org.elasticsearch.cluster.node.DiscoveryNodes
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.common.settings.{ClusterSettings, IndexScopedSettings, Settings, SettingsFilter}
import org.elasticsearch.common.util.concurrent.ThreadContext
import org.elasticsearch.common.util.set.Sets
import org.elasticsearch.common.xcontent._
import org.elasticsearch.index.reindex.ReindexPlugin
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService
import org.elasticsearch.plugins.ActionPlugin
import org.elasticsearch.plugins.ActionPlugin.ActionHandler
import org.elasticsearch.rest._
import org.elasticsearch.script.mustache.MustachePlugin
import org.elasticsearch.tasks
import org.elasticsearch.tasks.TaskManager
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.usage.UsageService
import tech.beshu.ror.boot.StartingFailure
import tech.beshu.ror.es.rradmin._
import tech.beshu.ror.es.rradmin.rest.RestRRAdminAction
import tech.beshu.ror.es.utils.ThreadRepo
import tech.beshu.ror.providers.EnvVarsProvider
import tech.beshu.ror.proxy.es.EsActionRequestHandler.HandlingResult
import tech.beshu.ror.proxy.es.EsRestServiceSimulator.ProcessingResult
import tech.beshu.ror.proxy.es.clients.{EsRestNodeClient, RestHighLevelClientAdapter}
import tech.beshu.ror.proxy.es.genericaction.{GenericAction, GenericRequest, GenericResponseActionListener}
import tech.beshu.ror.proxy.es.services.ProxyIndexJsonContentService
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.TaskOps._

import scala.collection.JavaConverters._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

class EsRestServiceSimulator(simulatorEsSettings: File,
                             proxyFilter: ProxyIndexLevelActionFilter,
                             esClient: RestHighLevelClientAdapter,
                             rrAdminActionHandler: RRAdminActionHandler,
                             threadPool: ThreadPool)
                            (implicit scheduler: Scheduler)
  extends Logging {

  private val settings = Settings.fromXContent(
    XContentFactory
      .xContent(XContentType.JSON)
      .createParser(
        NamedXContentRegistry.EMPTY,
        DeprecationHandler.THROW_UNSUPPORTED_OPERATION,
        simulatorEsSettings.contentAsString
      )
  )
  private val nodeClient = new EsRestNodeClient(new NodeClient(settings, threadPool), proxyFilter, esClient, settings, threadPool)
  private val actionModule: ActionModule = configureSimulator()

  def processRequest(request: RestRequest): Task[ProcessingResult] = {
    val restChannel = new ProxyRestChannel(request)
    val executionResult = Try {
      GenericRequest.from(request) match {
        case Some(genericRequest) =>
          processDirectly(genericRequest, restChannel)
        case None =>
          processThroughEsInternals(request, restChannel)
      }
    }
    executionResult match {
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

  private def processDirectly(request: GenericRequest, restChannel: ProxyRestChannel): Unit = {
    proxyFilter
      .execute(GenericAction.NAME, request, new GenericResponseActionListener(restChannel))(
        esClient.generic
      )
  }

  private def processThroughEsInternals(request: RestRequest, restChannel: ProxyRestChannel): Unit = {
    val threadContext = threadPool.getThreadContext
    threadContext.stashContext.bracket { _ =>
      ProxyThreadRepo.setRestChannel(restChannel)
      actionModule.getRestController
        .dispatchRequest(request, restChannel, threadContext)
    }
  }

  def stop(): Task[Unit] = proxyFilter.stop()

  private def configureSimulator() = {
    createActionModule(settings)
  }

  private def createActionModule(settings: Settings) = {
    val clusterSettings = new ClusterSettings(settings, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS, Sets.newHashSet())
    val circuitBreakerService = new NoneCircuitBreakerService()
    val usageService = new UsageService()
    val rorPlugin = new RORActionPlugin
    val clusterService = new ClusterService(settings, clusterSettings, threadPool)
    clusterService.getClusterApplierService.setInitialState(ClusterState.EMPTY_STATE)
    val allPlugins = supportedPlugins(rorPlugin)
    val actionModule = new ActionModule(
      false,
      settings,
      new IndexNameExpressionResolver(),
      IndexScopedSettings.DEFAULT_SCOPED_SETTINGS,
      clusterSettings,
      new SettingsFilter(List.empty.asJava),
      threadPool,
      allPlugins.asJava,
      nodeClient,
      circuitBreakerService,
      usageService,
      clusterService
    )

    val taskManager = new TaskManager(settings, threadPool, Set.empty[String].asJava)
    val esActionRequestHandler = new EsActionRequestHandler(esClient, clusterService)
    nodeClient.initialize(
      actions(
        actionModule,
        new ActionFilters(Set[ActionFilter](proxyFilter).asJava),
        taskManager,
        esActionRequestHandler
      ),
      taskManager,
      () => "ROR_proxy",
      null
    )

    actionModule.initRestHandlers(() => DiscoveryNodes.EMPTY_NODES)
    actionModule
  }

  private def supportedPlugins(rorPlugin: RORActionPlugin) = List[ActionPlugin](
    rorPlugin,
    new ReindexPlugin(),
    new MustachePlugin()
  )

  private def actions(actionModule: ActionModule,
                      afs: ActionFilters,
                      tm: TaskManager,
                      esActionRequestHandler: EsActionRequestHandler): util.Map[ActionType[_ <: ActionResponse], TransportAction[_ <: ActionRequest, _ <: ActionResponse]] = {
    val map: util.HashMap[ActionType[_ <: ActionResponse], TransportAction[_ <: ActionRequest, _ <: ActionResponse]] = Maps.newHashMap()
    actionModule
      .getActions.asScala
      .foreach { case (_, action) =>
        map.put(
          actionTypeOf(action),
          transportAction[ActionRequest, ActionResponse](esActionRequestHandler, action.getAction.name(), afs, tm)
        )
      }
    map
  }

  private def actionTypeOf(actionHandler: ActionHandler[_ <: ActionRequest, _ <: ActionResponse]): ActionType[_ <: ActionResponse] = {
    actionHandler.getAction
  }

  private def transportAction[R <: ActionRequest, RR <: ActionResponse](esActionRequestHandler: EsActionRequestHandler,
                                                                        actionName: String,
                                                                        actionFilters: ActionFilters,
                                                                        taskManager: TaskManager): TransportAction[_ <: ActionRequest, _ <: ActionResponse] =
    new TransportAction[R, RR](actionName, actionFilters, taskManager) {
      override def doExecute(task: tasks.Task, request: R, listener: ActionListener[RR]): Unit = {
        ProxyThreadRepo.getRestChannel match {
          case Some(proxyRestChannel) =>
            (request, listener) match {
              case (req: RRAdminRequest, resp: ActionListener[RRAdminResponse]) =>
                rrAdminActionHandler.handle(req, resp)
              case _ =>
                handleEsAction(esActionRequestHandler, request, listener, proxyRestChannel)
            }
          case None =>
            EsRestServiceSimulator.this.logger.warn(s"Request $request won't be executed")
        }
      }
    }

  private def handleEsAction[R <: ActionRequest, RR <: ActionResponse](esActionRequestHandler: EsActionRequestHandler,
                                                                       request: R,
                                                                       listener: ActionListener[RR],
                                                                       proxyRestChannel: ProxyRestChannel) = {
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
  }

  private def sendResponseThroughChannel(proxyRestChannel: ProxyRestChannel,
                                         response: ToXContent): Unit = {
    proxyRestChannel.sendResponse(new BytesRestResponse(
      RestStatus.OK,
      builderFrom(response, proxyRestChannel)
    ))
  }

  private def builderFrom(response: ToXContent, proxyRestChannel: ProxyRestChannel) = {
    response match {
      case r: ToXContentObject =>
        r.toXContent(proxyRestChannel.newBuilder(), ToXContent.EMPTY_PARAMS)
      case r: ToXContentFragment =>
        val builder = proxyRestChannel.newBuilder()
        builder.startObject()
        r.toXContent(builder, ToXContent.EMPTY_PARAMS)
        builder.endObject()
    }
  }

  private def sendResponseThroughChannel(proxyRestChannel: ProxyRestChannel,
                                         response: StatusToXContentObject): Unit = {
    proxyRestChannel.sendResponse(new BytesRestResponse(
      response.status(),
      response.toXContent(proxyRestChannel.newBuilder(), ToXContent.EMPTY_PARAMS)
    ))
  }

  private class RORActionPlugin extends ActionPlugin {

    override def getActions: util.List[ActionHandler[_ <: ActionRequest, _ <: ActionResponse]] = {
      List[ActionPlugin.ActionHandler[_ <: ActionRequest, _ <: ActionResponse]](
        new ActionHandler(RRAdminActionType.instance, classOf[TransportRRAdminAction])
      ).asJava
    }

    override def getRestHandlers(settings: Settings,
                                 restController: RestController,
                                 clusterSettings: ClusterSettings,
                                 indexScopedSettings: IndexScopedSettings,
                                 settingsFilter: SettingsFilter,
                                 indexNameExpressionResolver: IndexNameExpressionResolver,
                                 nodesInCluster: Supplier[DiscoveryNodes]): util.List[RestHandler] = {
      List[RestHandler](
        new RestRRAdminAction(restController)
      ).asJava
    }

    override def getRestHandlerWrapper(threadContext: ThreadContext): UnaryOperator[RestHandler] = {
      restHandler: RestHandler =>
        (request: RestRequest, channel: RestChannel, client: NodeClient) => {
          ThreadRepo.setRestChannel(channel)
          request.params().asScala.foreach { case (name, _) => request.param(name) } // todo: consuming all params (find a better way)
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
             esConfigFile: File,
             threadPool: ThreadPool)
            (implicit scheduler: Scheduler,
             envVarsProvider: EnvVarsProvider): Task[Either[StartingFailure, EsRestServiceSimulator]] = {
    val simulatorEsSettingsFolder = esConfigFile.parent.path
    val rrAdminActionHandler = new RRAdminActionHandler(ProxyIndexJsonContentService, simulatorEsSettingsFolder)
    val result = for {
      filter <- EitherT(ProxyIndexLevelActionFilter.create(simulatorEsSettingsFolder, esClient, threadPool))
    } yield new EsRestServiceSimulator(esConfigFile, filter, esClient, rrAdminActionHandler, threadPool)
    result.value
  }
}

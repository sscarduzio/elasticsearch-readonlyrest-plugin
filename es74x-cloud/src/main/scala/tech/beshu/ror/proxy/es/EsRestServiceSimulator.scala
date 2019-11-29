/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es

import java.util
import java.util.Collections

import better.files.File
import cats.data.EitherT
import com.google.common.collect.Maps
import monix.eval.Task
import monix.execution.Scheduler
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action._
import org.elasticsearch.action.support.{ActionFilter, ActionFilters, TransportAction}
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.cluster.node.DiscoveryNodes
import org.elasticsearch.common.settings.{ClusterSettings, IndexScopedSettings, Settings}
import org.elasticsearch.common.util.set.Sets
import org.elasticsearch.common.xcontent.{StatusToXContentObject, ToXContent, XContentFactory, XContentType}
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService
import org.elasticsearch.plugins.ActionPlugin.ActionHandler
import org.elasticsearch.rest.{BytesRestResponse, RestRequest, RestResponse}
import org.elasticsearch.tasks
import org.elasticsearch.tasks.TaskManager
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.usage.UsageService
import tech.beshu.ror.boot.StartingFailure
import tech.beshu.ror.proxy.es.EsActionRequestHandler.HandlingResult
import tech.beshu.ror.proxy.es.EsRestServiceSimulator.ProcessingResult
import tech.beshu.ror.proxy.es.clients.{EsRestNodeClient, RestHighLevelClientAdapter}
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.TaskOps._

import scala.collection.JavaConverters._

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
    threadContext.stashContext.bracket { _ =>
      ProxyThreadRepo.setRestChannel(restChannel)
      actionModule.getRestController
        .dispatchRequest(request, restChannel, threadContext)
    }
    restChannel
      .result
      .andThen { case _ =>
        ProxyThreadRepo.clearRestChannel()
      }
  }

  def stop(): Task[Unit] = proxyFilter.stop()

  private def configureSimulator() = {

    val settings = Settings.fromXContent(
      XContentFactory
        .xContent(XContentType.JSON)
        .createParser(null, null, simulatorEsSettings.contentAsString)
    )
    createActionModule(settings)
  }

  private def createActionModule(settings: Settings) = {
    val nodeClient = new EsRestNodeClient(new NodeClient(settings, threadPool), esClient, settings, threadPool)
    val actionModule = new ActionModule(
      false,
      settings,
      null,
      IndexScopedSettings.DEFAULT_SCOPED_SETTINGS,
      new ClusterSettings(settings, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS, Sets.newHashSet()),
      null,
      threadPool,
      Collections.emptyList(),
      nodeClient,
      new NoneCircuitBreakerService(),
      new UsageService()
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
              .foreach {
                case HandlingResult.Handled(response) => sendResponseThroughChannel(proxyRestChannel, response)
                case HandlingResult.PassItThrough => proxyRestChannel.passThrough()
              }
          case None =>
            EsRestServiceSimulator.this.logger.warn(s"Request $request won't be executed")
        }
      }
    }

  private def sendResponseThroughChannel(proxyRestChannel: ProxyRestChannel,
                                         response: StatusToXContentObject): Unit = {
    proxyRestChannel.sendResponse(new BytesRestResponse(
      response.status(),
      response.toXContent(proxyRestChannel.newBuilder(), ToXContent.EMPTY_PARAMS)
    ))
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

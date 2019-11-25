package tech.beshu.ror.es.proxy

import java.util
import java.util.Collections

import better.files.File
import cats.data.EitherT
import com.google.common.collect.Maps
import monix.eval.Task
import org.apache.http.HttpHost
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action._
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.action.support.{ActionFilter, ActionFilters, TransportAction}
import org.elasticsearch.client.{RequestOptions, RestClient, RestHighLevelClient}
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.cluster.node.DiscoveryNodes
import org.elasticsearch.common.settings.{ClusterSettings, IndexScopedSettings, Settings}
import org.elasticsearch.common.util.set.Sets
import org.elasticsearch.common.xcontent.{ToXContent, XContentFactory, XContentType}
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService
import org.elasticsearch.plugins.ActionPlugin.ActionHandler
import org.elasticsearch.rest.{BytesRestResponse, RestRequest, RestResponse}
import org.elasticsearch.tasks
import org.elasticsearch.tasks.TaskManager
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.usage.UsageService
import tech.beshu.ror.boot.StartingFailure
import tech.beshu.ror.es.proxy.EsRestServiceSimulator.Result
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.TaskOps._

import scala.collection.JavaConverters._
import scala.util.Try

class EsRestServiceSimulator(simulatorEsSettings: File,
                             proxyFilter: ProxyIndexLevelActionFilter,
                             threadPool: ThreadPool)
  extends Logging {

  private val esClient = new RestHighLevelClient(RestClient.builder(HttpHost.create("http://localhost:9201"))) // todo: configuration
  private val actionModule: ActionModule = configureSimulator()

  def processRequest(request: RestRequest): Task[Result] = {
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
    val nodeClient = new EsRestNodeClient(new NodeClient(settings, threadPool), settings, threadPool)
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
            request match {
              case sr: SearchRequest =>
                Try(esClient.search(sr, RequestOptions.DEFAULT))
                  .foreach { resp =>
                    proxyRestChannel.sendResponse(new BytesRestResponse(
                      resp.status(),
                      resp.toXContent(proxyRestChannel.newBuilder(), ToXContent.EMPTY_PARAMS)
                    ))
                  }
              case _ =>
                proxyRestChannel.passThrough()
            }
          case None =>
            EsRestServiceSimulator.this.logger.warn(s"Request $request won't be executed")
        }
      }
    }

}

object EsRestServiceSimulator {

  sealed trait Result
  object Result {
    final case class Response(restResponse: RestResponse) extends Result
    case object PassThrough extends Result
  }

  def create(threadPool: ThreadPool): Task[Either[StartingFailure, EsRestServiceSimulator]] = {
    val simulatorEsSettingsFile = File(getClass.getClassLoader.getResource("elasticsearch.yml"))
    val simulatorEsSettingsFolder = simulatorEsSettingsFile.parent.path
    val result = for {
      filter <- EitherT(ProxyIndexLevelActionFilter.create(simulatorEsSettingsFolder, threadPool))
    } yield new EsRestServiceSimulator(simulatorEsSettingsFile, filter, threadPool)
    result.value
  }
}

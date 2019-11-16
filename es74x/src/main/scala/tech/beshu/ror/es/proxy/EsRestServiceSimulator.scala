package tech.beshu.ror.es.proxy

import java.util
import java.util.Collections

import better.files.File
import cats.data.EitherT
import com.google.common.collect.Maps
import monix.eval.Task
import org.elasticsearch.action._
import org.elasticsearch.action.support.{ActionFilter, ActionFilters, TransportAction}
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.cluster.block.ClusterBlocks
import org.elasticsearch.cluster.node.DiscoveryNodes
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.cluster.{ClusterName, ClusterState}
import org.elasticsearch.common.settings.{ClusterSettings, IndexScopedSettings, Settings}
import org.elasticsearch.common.util.set.Sets
import org.elasticsearch.common.xcontent.{XContentFactory, XContentType}
import org.elasticsearch.gateway.GatewayService
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService
import org.elasticsearch.plugins.ActionPlugin.ActionHandler
import org.elasticsearch.rest.{RestRequest, RestResponse}
import org.elasticsearch.tasks
import org.elasticsearch.tasks.TaskManager
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.usage.UsageService
import tech.beshu.ror.boot.StartingFailure
import tech.beshu.ror.es.proxy.EsRestServiceSimulator.Result
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.TaskOps._

import scala.collection.JavaConverters._

class EsRestServiceSimulator(simulatorEsSettings: File,
                             proxyFilter: ProxyIndexLevelActionFilter) {

  private val threadPool: ThreadPool = new ThreadPool(Settings.EMPTY)
  private val actionModule: ActionModule = configureSimulator

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

  private def configureSimulator = {
    val xc = XContentFactory
      .xContent(XContentType.JSON)
      .createParser(null, null, simulatorEsSettings.contentAsString)

    val csettings = Settings.fromXContent(xc)
    val nc = new NodeClient(csettings, threadPool)

    val cbs = new NoneCircuitBreakerService()
    val us = new UsageService()
    val cs = new ClusterSettings(csettings, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS, Sets.newHashSet())

    val clusterService = new ClusterService(csettings, cs, threadPool)
    clusterService
      .getClusterApplierService
      .setInitialState(
        ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.get(csettings))
          .blocks(ClusterBlocks.builder().addGlobalBlock(GatewayService.STATE_NOT_RECOVERED_BLOCK))
          .build()
      )

    val actionModule = new ActionModule(
      false,
      csettings,
      null,
      IndexScopedSettings.DEFAULT_SCOPED_SETTINGS,
      cs,
      null,
      threadPool,
      Collections.emptyList(),
      nc,
      cbs,
      us
    )

    val afs = new ActionFilters(Set[ActionFilter](proxyFilter).asJava)
    val tm = new TaskManager(csettings, threadPool, Set.empty[String].asJava)

    nc.initialize(
      actions(actionModule, afs, tm),
      null,
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

  private def transportAction[R <: ActionRequest, RR <: ActionResponse](str: String,
                                                                        afs: ActionFilters,
                                                                        tm: TaskManager): TransportAction[_ <: ActionRequest, _ <: ActionResponse] =
    new TransportAction[R, RR](str, afs, tm) {
      override def doExecute(task: tasks.Task, request: R, listener: ActionListener[RR]): Unit =
        ProxyThreadRepo.getRestChannel match {
          case Some(proxyRestChannel) =>
            proxyRestChannel.passThrough()
          case None =>
            throw new Exception("!!") //todo:
        }
    }

}

object EsRestServiceSimulator {

  sealed trait Result
  object Result {
    final case class Response(restResponse: RestResponse) extends Result
    case object PassThrough extends Result
  }

  def create(): Task[Either[StartingFailure, EsRestServiceSimulator]] = {
    val simulatorEsSettingsFile = File(getClass.getClassLoader.getResource("elasticsearch.yml"))
    val simulatorEsSettingsFolder = simulatorEsSettingsFile.parent.path
    val result = for {
      filter <- EitherT(ProxyIndexLevelActionFilter.create(simulatorEsSettingsFolder))
    } yield new EsRestServiceSimulator(simulatorEsSettingsFile, filter)
    result.value
  }
}

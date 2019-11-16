package tech.beshu.ror.es.proxy

import java.util
import java.util.Collections

import better.files.File
import cats.data.EitherT
import monix.eval.Task
import org.elasticsearch.action._
import org.elasticsearch.action.support.{ActionFilter, ActionFilters, TransportAction}
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.cluster.block.ClusterBlocks
import org.elasticsearch.cluster.node.DiscoveryNodes
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.cluster.{ClusterName, ClusterState}
import org.elasticsearch.common.io.stream.StreamOutput
import org.elasticsearch.common.settings.{ClusterSettings, IndexScopedSettings, Settings}
import org.elasticsearch.common.util.set.Sets
import org.elasticsearch.common.xcontent.{XContentFactory, XContentType}
import org.elasticsearch.gateway.GatewayService
import org.elasticsearch.indices.breaker.NoneCircuitBreakerService
import org.elasticsearch.rest.{RestRequest, RestResponse}
import org.elasticsearch.tasks
import org.elasticsearch.tasks.TaskManager
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.usage.UsageService
import tech.beshu.ror.boot.StartingFailure
import tech.beshu.ror.es.utils.ThreadRepo
import tech.beshu.ror.utils.ScalaOps._

import scala.collection.JavaConverters._

class EsRestServiceSimulator(simulatorEsSettings: File,
                             proxyFilter: ProxyIndexLevelActionFilter) {

  private val threadPool: ThreadPool = new ThreadPool(Settings.EMPTY)
  private val actionModule: ActionModule = configureSimulator

  def processRequest(request: RestRequest): Task[RestResponse] = {
    val restChannel = new ProxyRestChannel(request)
    val threadContext = threadPool.getThreadContext
    threadContext.stashContext.bracket { _ =>
      ThreadRepo.setRestChannel(restChannel)
      actionModule.getRestController
        .dispatchRequest(request, restChannel, threadContext)
    }
    restChannel.response
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
      test(actionModule, afs, tm).asInstanceOf[util.Map[ActionType[_ <: ActionResponse], TransportAction[_ <: ActionRequest, _ <: ActionResponse]]],
      null,
      null
    )

    actionModule.initRestHandlers(() => DiscoveryNodes.EMPTY_NODES)
    actionModule
  }

  private def test(actionModule: ActionModule,
                   afs: ActionFilters,
                   tm: TaskManager): util.Map[ActionType[DummyActionResponse], TransportAction[DummyActionRequest, DummyActionResponse]] = {
    actionModule
      .getActions.asScala
      .map { case (_, action) =>
        (
          action.getAction.asInstanceOf[ActionType[DummyActionResponse]],
          transportAction(action.getAction.name(), afs, tm)
        )
      }
      .toMap
      .asJava
  }

  private def transportAction(name: String,
                              afs: ActionFilters,
                              tm: TaskManager) =
    new TransportAction[DummyActionRequest, DummyActionResponse](name, afs, tm) {
      override def doExecute(task: tasks.Task,
                             request: DummyActionRequest,
                             listener: ActionListener[DummyActionResponse]): Unit = ()
    }

  class DummyActionRequest extends ActionRequest {
    override def validate(): ActionRequestValidationException = ???
  }
  class DummyActionResponse extends ActionResponse {
    override def writeTo(out: StreamOutput): Unit = ()
  }
}

object EsRestServiceSimulator {

  def create(): Task[Either[StartingFailure, EsRestServiceSimulator]] = {
    val simulatorEsSettingsFile = File(getClass.getClassLoader.getResource("elasticsearch.yml"))
    val simulatorEsSettingsFolder = simulatorEsSettingsFile.parent.path
    val result = for {
      filter <- EitherT(ProxyIndexLevelActionFilter.create(simulatorEsSettingsFolder))
    } yield new EsRestServiceSimulator(simulatorEsSettingsFile, filter)
    result.value
  }
}

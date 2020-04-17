package tech.beshu.ror.es.rrconfig

import java.util

import monix.eval.Task
import org.elasticsearch.action.FailedNodeException
import org.elasticsearch.action.support.ActionFilters
import org.elasticsearch.action.support.nodes.TransportNodesAction
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.common.io.stream.{StreamInput, Writeable}
import org.elasticsearch.env.Environment
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.transport.TransportService
import tech.beshu.ror.es.providers.EsIndexJsonContentProvider
import tech.beshu.ror.es.rrconfig
import tech.beshu.ror.providers.{EnvVarsProvider, OsEnvVarsProvider}

import scala.concurrent.duration._
import scala.language.postfixOps

class TransportRRConfigAction(actionName: String,
                              threadPool: ThreadPool,
                              clusterService: ClusterService,
                              transportService: TransportService,
                              actionFilters: ActionFilters,
                              env: Environment,
                              indexContentProvider: EsIndexJsonContentProvider,
                              request: Writeable.Reader[RRConfigsRequest],
                              nodeRequest: Writeable.Reader[RRConfigRequest],
                              nodeExecutor: String,
                              nodeResponseClass: Class[RRConfig],
                              constructorDiscriminator: Unit)
  extends TransportNodesAction[RRConfigsRequest, RRConfigsResponse, RRConfigRequest, RRConfig](
    actionName,
    threadPool,
    clusterService,
    transportService,
    actionFilters,
    request,
    nodeRequest,
    nodeExecutor,
    nodeResponseClass
  ) {

  import monix.execution.Scheduler.Implicits.global

  implicit val envVarsProvider: EnvVarsProvider = OsEnvVarsProvider
  private val loader = new ConfigLoader(env, indexContentProvider)

  @Inject
  def this(actionName: String,
           threadPool: ThreadPool,
           clusterService: ClusterService,
           transportService: TransportService,
           actionFilters: ActionFilters,
           env: Environment,
           indexContentProvider: EsIndexJsonContentProvider,
          ) =
    this(
      RRConfigAction.name,
      threadPool,
      clusterService,
      transportService,
      actionFilters,
      env,
      indexContentProvider,
      new RRConfigsRequest(_),
      new rrconfig.RRConfigRequest(_),
      ThreadPool.Names.GENERIC,
      classOf[RRConfig],
      ()
    )

  override def newResponse(request: RRConfigsRequest, responses: util.List[RRConfig], failures: util.List[FailedNodeException]): RRConfigsResponse = {
    println(s"new response $request, $responses, $failures")
    new RRConfigsResponse(clusterService.getClusterName, responses, failures)
  }

  override def newNodeRequest(request: RRConfigsRequest): RRConfigRequest =
    new RRConfigRequest(request.getNodeConfigRequest)

  override def newNodeResponse(in: StreamInput): RRConfig =
    new RRConfig(in)

  override def nodeOperation(request: RRConfigRequest): RRConfig = {
    val nodeRequest = request.getNodeConfigRequest
    val nodeResponse =
      loader.load()
//        .delayExecution(1 second)
//        .timeoutTo(nodeRequest.timeout.nanos nanos, Task.pure(Left(LoadedConfig.Timeout)))
        .runSyncUnsafe(nodeRequest.timeout.nanos nanos)
    new RRConfig(clusterService.localNode(), NodeConfig(nodeResponse))
  }

}



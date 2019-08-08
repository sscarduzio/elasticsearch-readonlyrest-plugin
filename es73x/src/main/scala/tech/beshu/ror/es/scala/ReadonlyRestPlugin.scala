package tech.beshu.ror.es.scala

import java.nio.file.Path
import java.security.{AccessController, PrivilegedAction}
import java.util
import java.util.function.{Supplier, UnaryOperator}

import monix.execution.Scheduler
import monix.execution.schedulers.CanBlock
import org.elasticsearch.ElasticsearchException
import org.elasticsearch.action.support.ActionFilter
import org.elasticsearch.action.{ActionRequest, ActionResponse}
import org.elasticsearch.client.Client
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver
import org.elasticsearch.cluster.node.DiscoveryNodes
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.common.component.LifecycleComponent
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.common.io.stream.NamedWriteableRegistry
import org.elasticsearch.common.network.NetworkService
import org.elasticsearch.common.settings._
import org.elasticsearch.common.util.concurrent.ThreadContext
import org.elasticsearch.common.util.{BigArrays, PageCacheRecycler}
import org.elasticsearch.common.xcontent.NamedXContentRegistry
import org.elasticsearch.env.{Environment, NodeEnvironment}
import org.elasticsearch.http.HttpServerTransport
import org.elasticsearch.index.IndexModule
import org.elasticsearch.index.mapper.MapperService
import org.elasticsearch.indices.breaker.CircuitBreakerService
import org.elasticsearch.plugins.ActionPlugin.ActionHandler
import org.elasticsearch.plugins._
import org.elasticsearch.rest.{RestChannel, RestController, RestHandler, RestRequest}
import org.elasticsearch.script.ScriptService
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.transport.Transport
import org.elasticsearch.watcher.ResourceWatcherService
import tech.beshu.ror.Constants
import tech.beshu.ror.configuration.RorSsl
import tech.beshu.ror.es.rradmin.rest.RestRRAdminAction
import tech.beshu.ror.es.rradmin.{RRAdminAction, TransportRRAdminAction}
import tech.beshu.ror.es.security.RoleIndexSearcherWrapper
import tech.beshu.ror.es.IndexLevelActionFilter

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.postfixOps

@Inject
class ReadonlyRestPlugin(s: Settings, p: Path)
  extends Plugin
    with ScriptPlugin
    with ActionPlugin
    with IngestPlugin
    with NetworkPlugin {

  Constants.FIELDS_ALWAYS_ALLOW.addAll(MapperService.getAllMetaFields.toList.asJava)

  private val environment = new Environment(s, p)
  private val timeout: FiniteDuration = 10 seconds
  private val sslConfig = RorSsl
    .load(environment.configFile)
    .map(_.fold(e => throw new ElasticsearchException(e.message), identity))
    .runSyncUnsafe(timeout)(Scheduler.global, CanBlock.permit)

  private var ilaf: IndexLevelActionFilter = _

  override def createComponents(client: Client,
                                clusterService: ClusterService,
                                threadPool: ThreadPool,
                                resourceWatcherService: ResourceWatcherService,
                                scriptService: ScriptService,
                                xContentRegistry: NamedXContentRegistry,
                                environment: Environment,
                                nodeEnvironment: NodeEnvironment,
                                namedWriteableRegistry: NamedWriteableRegistry): util.Collection[AnyRef] = {
    AccessController.doPrivileged(new PrivilegedAction[Unit] {
      override def run(): Unit = {
        ilaf = new IndexLevelActionFilter(clusterService, client.asInstanceOf[NodeClient], threadPool, environment, TransportServiceInterceptor.remoteClusterServiceSupplier)
      }
    })
    List.empty[AnyRef].asJava
  }

  override def getGuiceServiceClasses: util.Collection[Class[_ <: LifecycleComponent]] = {
    List[Class[_ <: LifecycleComponent]](classOf[TransportServiceInterceptor]).asJava
  }

  override def getActionFilters: util.List[ActionFilter] = {
    List[ActionFilter](ilaf).asJava
  }

  override def getTaskHeaders: util.Collection[String] = {
    List(Constants.FILTER_TRANSIENT, Constants.FIELDS_TRANSIENT).asJava
  }

  override def onIndexModule(indexModule: IndexModule): Unit = {
    indexModule.setReaderWrapper(new RoleIndexSearcherWrapper())
  }

  override def getSettings: util.List[Setting[_]] = {
    List[Setting[_]](Setting.groupSetting("readonlyrest.", Setting.Property.Dynamic, Setting.Property.NodeScope)).asJava
  }

  override def getHttpTransports(settings: Settings,
                                 threadPool: ThreadPool,
                                 bigArrays: BigArrays,
                                 pageCacheRecycler: PageCacheRecycler,
                                 circuitBreakerService: CircuitBreakerService,
                                 xContentRegistry: NamedXContentRegistry,
                                 networkService: NetworkService,
                                 dispatcher: HttpServerTransport.Dispatcher): util.Map[String, Supplier[HttpServerTransport]] = {
    sslConfig
      .externalSsl
      .map(ssl =>
        "ssl_netty4" -> new Supplier[HttpServerTransport] {
          override def get(): HttpServerTransport = new SSLNetty4HttpServerTransport(settings, networkService, bigArrays, threadPool, xContentRegistry, dispatcher, ssl)
        }
      )
      .toMap
      .asJava
  }

  override def getTransports(settings: Settings,
                             threadPool: ThreadPool,
                             pageCacheRecycler: PageCacheRecycler,
                             circuitBreakerService: CircuitBreakerService,
                             namedWriteableRegistry: NamedWriteableRegistry,
                             networkService: NetworkService): util.Map[String, Supplier[Transport]] = {
    sslConfig
      .interNodeSsl
      .map(ssl =>
        "ror_ssl_internode" -> new Supplier[Transport] {
          override def get(): Transport = new SSLNetty4InternodeServerTransport(settings, threadPool, pageCacheRecycler, circuitBreakerService, namedWriteableRegistry, networkService, ssl)
        }
      )
      .toMap
      .asJava
  }

  override def close(): Unit = {
    ilaf.stop()
  }

  override def getActions: util.List[ActionPlugin.ActionHandler[_ <: ActionRequest, _ <: ActionResponse]] = {
    List[ActionPlugin.ActionHandler[_ <: ActionRequest, _ <: ActionResponse]](
      new ActionHandler(RRAdminAction.INSTANCE, classOf[TransportRRAdminAction])
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
      new RestRRAdminAction(settings, restController)
    ).asJava
  }

  override def getRestHandlerWrapper(threadContext: ThreadContext): UnaryOperator[RestHandler] = {
    restHandler: RestHandler =>
      (request: RestRequest, channel: RestChannel, client: NodeClient) => {
        ThreadRepo.channel.set(channel)
        restHandler.handleRequest(request, channel, client)
      }
  }
}
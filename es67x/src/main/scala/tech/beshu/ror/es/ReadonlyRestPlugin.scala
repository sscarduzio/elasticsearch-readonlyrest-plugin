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
package tech.beshu.ror.es

import java.nio.file.Path
import java.util
import java.util.function.{Supplier, UnaryOperator}
import monix.execution.Scheduler
import monix.execution.schedulers.CanBlock
import org.elasticsearch.{ElasticsearchException, Version}
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse
import org.elasticsearch.action.support.ActionFilter
import org.elasticsearch.action.{ActionRequest, ActionResponse}
import org.elasticsearch.client.Client
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver
import org.elasticsearch.cluster.node.DiscoveryNodes
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.cluster.{ClusterName, ClusterState}
import org.elasticsearch.common.component.LifecycleComponent
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.common.io.stream.NamedWriteableRegistry
import org.elasticsearch.common.network.NetworkService
import org.elasticsearch.common.settings._
import org.elasticsearch.common.util.concurrent.{EsExecutors, ThreadContext}
import org.elasticsearch.common.util.{BigArrays, PageCacheRecycler}
import org.elasticsearch.common.xcontent.NamedXContentRegistry
import org.elasticsearch.discovery.zen.PublishClusterStateAction.serializeFullClusterState
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
import org.elasticsearch.transport.{Transport, TransportInterceptor}
import org.elasticsearch.transport.netty4.Netty4Utils
import org.elasticsearch.watcher.ResourceWatcherService
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.matchers.{RandomBasedUniqueIdentifierGenerator, UniqueIdentifierGenerator}
import tech.beshu.ror.boot.EsInitListener
import tech.beshu.ror.configuration.RorSsl
import tech.beshu.ror.es.actions.rradmin.rest.RestRRAdminAction
import tech.beshu.ror.es.actions.rradmin.{RRAdminActionType, TransportRRAdminAction}
import tech.beshu.ror.es.dlsfls.RoleIndexSearcherWrapper
import tech.beshu.ror.es.actions.rrconfig.rest.RestRRConfigAction
import tech.beshu.ror.es.actions.rrconfig.{RRConfigActionType, TransportRRConfigAction}
import tech.beshu.ror.es.ssl.{SSLNetty4HttpServerTransport, SSLNetty4InternodeServerTransport}
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged
import tech.beshu.ror.es.utils.ThreadRepo
import tech.beshu.ror.providers.{EnvVarsProvider, JvmPropertiesProvider, OsEnvVarsProvider, PropertiesProvider}
import tech.beshu.ror.buildinfo.LogPluginBuildInfoMessage
import tech.beshu.ror.es.actions.rrauditevent.{RRAuditEventActionType, TransportRRAuditEventAction}
import tech.beshu.ror.es.actions.rrauditevent.rest.RestRRAuditEventAction
import tech.beshu.ror.es.actions.rrauthmock.rest.RestRRAuthMockAction
import tech.beshu.ror.es.actions.rrauthmock.{RRAuthMockActionType, TransportRRAuthMockAction}
import tech.beshu.ror.es.actions.rrmetadata.{RRUserMetadataActionType, TransportRRUserMetadataAction}
import tech.beshu.ror.es.actions.rrmetadata.rest.RestRRUserMetadataAction

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.postfixOps

@Inject
class ReadonlyRestPlugin(s: Settings, p: Path)
  extends Plugin
    with ScriptPlugin
    with ActionPlugin
    with IngestPlugin
    with NetworkPlugin
    with ClusterPlugin {

  LogPluginBuildInfoMessage()

  Constants.FIELDS_ALWAYS_ALLOW.addAll(MapperService.getAllMetaFields.toList.asJava)
  // ES uses Netty underlying and Finch also uses it under the hood. Seems that ES has reimplemented own available processor
  // flag check, which is also done by Netty. So, we need to set it manually before ES and Finch, otherwise we will
  // experience 'java.lang.IllegalStateException: availableProcessors is already set to [x], rejecting [x]' exception
  doPrivileged {
    Netty4Utils.setAvailableProcessors(EsExecutors.PROCESSORS_SETTING.get(s))
  }

  private implicit val envVarsProvider: EnvVarsProvider = OsEnvVarsProvider
  private implicit val propertiesProvider: PropertiesProvider = JvmPropertiesProvider
  private implicit val uniqueIdentifierGenerator: UniqueIdentifierGenerator = RandomBasedUniqueIdentifierGenerator

  private val environment = new Environment(s, p)
  private val timeout: FiniteDuration = 10 seconds
  private val sslConfig = RorSsl
    .load(environment.configFile)
    .map(_.fold(e => throw new ElasticsearchException(e.message), identity))
    .runSyncUnsafe(timeout)(Scheduler.global, CanBlock.permit)
  private val emptyClusterState = new ClusterStateResponse(
    ClusterName.CLUSTER_NAME_SETTING.get(s),
    ClusterState.EMPTY_STATE,
    serializeFullClusterState(ClusterState.EMPTY_STATE, Version.CURRENT).length,
    false
  )
  private val esInitListener = new EsInitListener

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
    doPrivileged {
      ilaf = new IndexLevelActionFilter(
        client.settings().get("node.name"),
        clusterService,
        client.asInstanceOf[NodeClient],
        threadPool,
        environment,
        TransportServiceInterceptor.remoteClusterServiceSupplier,
        SnapshotsServiceInterceptor.snapshotsServiceSupplier,
        emptyClusterState,
        esInitListener
      )
    }
    List.empty[AnyRef].asJava
  }

  override def getGuiceServiceClasses: util.Collection[Class[_ <: LifecycleComponent]] = {
    List[Class[_ <: LifecycleComponent]](
      classOf[TransportServiceInterceptor],
      classOf[SnapshotsServiceInterceptor]
    ).asJava
  }

  override def getActionFilters: util.List[ActionFilter] = {
    List[ActionFilter](ilaf).asJava
  }

  override def getTaskHeaders: util.Collection[String] = {
    List(Constants.FIELDS_TRANSIENT).asJava
  }

  override def onIndexModule(indexModule: IndexModule): Unit = {
    indexModule.setSearcherWrapper(new RoleIndexSearcherWrapper(_))
  }

  override def getSettings: util.List[Setting[_]] = {
    List[Setting[_]](Setting.groupSetting("readonlyrest.", Setting.Property.Dynamic, Setting.Property.NodeScope)).asJava
  }

  override def getHttpTransports(settings: Settings,
                                 threadPool: ThreadPool,
                                 bigArrays: BigArrays,
                                 circuitBreakerService: CircuitBreakerService,
                                 namedWriteableRegistry: NamedWriteableRegistry,
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
      new ActionHandler(RRAdminActionType.instance, classOf[TransportRRAdminAction]),
      new ActionHandler(RRAuthMockActionType.instance, classOf[TransportRRAuthMockAction]),
      new ActionHandler(RRConfigActionType.instance, classOf[TransportRRConfigAction]),
      new ActionHandler(RRUserMetadataActionType.instance, classOf[TransportRRUserMetadataAction]),
      new ActionHandler(RRAuditEventActionType.instance, classOf[TransportRRAuditEventAction]),
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
      new RestRRAdminAction(settings, restController),
      new RestRRAuthMockAction(settings, restController),
      new RestRRConfigAction(settings, restController, nodesInCluster),
      new RestRRUserMetadataAction(settings, restController),
      new RestRRAuditEventAction(settings, restController)
    ).asJava
  }

  override def getRestHandlerWrapper(threadContext: ThreadContext): UnaryOperator[RestHandler] = {
    restHandler: RestHandler =>
      (request: RestRequest, channel: RestChannel, client: NodeClient) => {
        val rorRestChannel = new RorRestChannel(channel)
        ThreadRepo.setRestChannel(rorRestChannel)
        restHandler.handleRequest(request, rorRestChannel, client)
      }
  }

  override def getTransportInterceptors(namedWriteableRegistry: NamedWriteableRegistry, threadContext: ThreadContext): util.List[TransportInterceptor] = {
    List[TransportInterceptor](new RorTransportInterceptor(threadContext, s.get("node.name"))).asJava
  }

  override def onNodeStarted(): Unit = {
    super.onNodeStarted()
    doPrivileged {
      esInitListener.onEsReady()
    }
  }
}
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

import monix.execution.Scheduler
import monix.execution.schedulers.CanBlock
import org.elasticsearch.ElasticsearchException
import org.elasticsearch.action.support.ActionFilter
import org.elasticsearch.action.{ActionRequest, ActionResponse}
import org.elasticsearch.client.internal.node.NodeClient
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver
import org.elasticsearch.cluster.node.DiscoveryNodes
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.common.io.stream.NamedWriteableRegistry
import org.elasticsearch.common.network.NetworkService
import org.elasticsearch.common.settings.*
import org.elasticsearch.common.util.concurrent.{EsExecutors, ThreadContext}
import org.elasticsearch.common.util.{BigArrays, PageCacheRecycler}
import org.elasticsearch.env.Environment
import org.elasticsearch.http.{HttpPreRequest, HttpServerTransport}
import org.elasticsearch.index.IndexModule
import org.elasticsearch.index.mapper.IgnoredFieldMapper
import org.elasticsearch.indices.breaker.CircuitBreakerService
import org.elasticsearch.plugins.ActionPlugin.ActionHandler
import org.elasticsearch.plugins.*
import org.elasticsearch.rest.{RestController, RestHandler}
import org.elasticsearch.telemetry.tracing.Tracer
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.transport.netty4.{Netty4Utils, SharedGroupFactory}
import org.elasticsearch.transport.{Transport, TransportInterceptor}
import org.elasticsearch.xcontent.NamedXContentRegistry
import tech.beshu.ror.boot.{EsInitListener, SecurityProviderConfiguratorForFips}
import tech.beshu.ror.buildinfo.LogPluginBuildInfoMessage
import tech.beshu.ror.configuration.{EnvironmentConfig, ReadonlyRestEsConfig}
import tech.beshu.ror.constants
import tech.beshu.ror.es.actions.rradmin.rest.RestRRAdminAction
import tech.beshu.ror.es.actions.rradmin.{RRAdminActionType, TransportRRAdminAction}
import tech.beshu.ror.es.actions.rrauditevent.rest.RestRRAuditEventAction
import tech.beshu.ror.es.actions.rrauditevent.{RRAuditEventActionType, TransportRRAuditEventAction}
import tech.beshu.ror.es.actions.rrauthmock.rest.RestRRAuthMockAction
import tech.beshu.ror.es.actions.rrauthmock.{RRAuthMockActionType, TransportRRAuthMockAction}
import tech.beshu.ror.es.actions.rrconfig.rest.RestRRConfigAction
import tech.beshu.ror.es.actions.rrconfig.{RRConfigActionType, TransportRRConfigAction}
import tech.beshu.ror.es.actions.rrmetadata.rest.RestRRUserMetadataAction
import tech.beshu.ror.es.actions.rrmetadata.{RRUserMetadataActionType, TransportRRUserMetadataAction}
import tech.beshu.ror.es.actions.rrtestconfig.rest.RestRRTestConfigAction
import tech.beshu.ror.es.actions.rrtestconfig.{RRTestConfigActionType, TransportRRTestConfigAction}
import tech.beshu.ror.es.actions.wrappers._cat.{RorWrappedCatActionType, TransportRorWrappedCatAction}
import tech.beshu.ror.es.actions.wrappers._upgrade.{RorWrappedUpgradeActionType, TransportRorWrappedUpgradeAction}
import tech.beshu.ror.es.dlsfls.RoleIndexSearcherWrapper
import tech.beshu.ror.es.ssl.{SSLNetty4HttpServerTransport, SSLNetty4InternodeServerTransport}
import tech.beshu.ror.es.utils.{ChannelInterceptingRestHandlerDecorator, EsEnvProvider, EsPatchVerifier, RemoteClusterServiceSupplier}
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged
import tech.beshu.ror.utils.SetOnce

import java.nio.file.Path
import java.util
import java.util.function.{BiConsumer, Supplier}
import scala.annotation.nowarn
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.language.postfixOps

@Inject
@nowarn("cat=deprecation")
class ReadonlyRestPlugin(s: Settings, p: Path)
  extends Plugin
    with ScriptPlugin
    with ActionPlugin
    with IngestPlugin
    with NetworkPlugin
    with ClusterPlugin {

  LogPluginBuildInfoMessage()
  EsPatchVerifier.verify(s)

  constants.FIELDS_ALWAYS_ALLOW.add(IgnoredFieldMapper.NAME)
  // ES uses Netty underlying and Finch also uses it under the hood. Seems that ES has reimplemented own available processor
  // flag check, which is also done by Netty. So, we need to set it manually before ES and Finch, otherwise we will
  // experience 'java.lang.IllegalStateException: availableProcessors is already set to [x], rejecting [x]' exception
  doPrivileged {
    Netty4Utils.setAvailableProcessors(EsExecutors.NODE_PROCESSORS_SETTING.get(s).roundDown())
  }

  private implicit val environmentConfig: EnvironmentConfig = EnvironmentConfig.default

  private val environment = new Environment(s, p)
  private val timeout: FiniteDuration = 10 seconds

  private val rorEsConfig = ReadonlyRestEsConfig
    .load(EsEnvProvider.create(environment))
    .map(_.fold(e => throw new ElasticsearchException(e.message), identity))
    .runSyncUnsafe(timeout)(Scheduler.global, CanBlock.permit)
  private val esInitListener = new EsInitListener
  private val groupFactory = new SetOnce[SharedGroupFactory]

  private var ilaf: IndexLevelActionFilter = _

  SecurityProviderConfiguratorForFips.configureIfRequired(rorEsConfig.fipsConfig)

  override def createComponents(services: Plugin.PluginServices): util.Collection[_] = {
    doPrivileged {
      val client = services.client()
      val repositoriesServiceSupplier = services.repositoriesServiceSupplier()
      ilaf = new IndexLevelActionFilter(
        EsNodeSettings(client.settings().get("node.name"), client.settings().get("cluster.name")),
        services.clusterService(),
        client.asInstanceOf[NodeClient],
        services.threadPool(),
        services.xContentRegistry(),
        environment,
        new RemoteClusterServiceSupplier(repositoriesServiceSupplier),
        () => Some(repositoriesServiceSupplier.get()),
        esInitListener,
        rorEsConfig
      )
    }
    List.empty[AnyRef].asJava
  }

  override def getActionFilters: util.List[ActionFilter] = {
    List[ActionFilter](ilaf).asJava
  }

  override def getTaskHeaders: util.Collection[String] = {
    List(constants.FIELDS_TRANSIENT).asJava
  }

  override def onIndexModule(indexModule: IndexModule): Unit = {
    import tech.beshu.ror.es.utils.IndexModuleOps.*
    indexModule.overwrite(RoleIndexSearcherWrapper.instance)
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
                                 dispatcher: HttpServerTransport.Dispatcher,
                                 perRequestThreadContext: BiConsumer[HttpPreRequest, ThreadContext],
                                 clusterSettings: ClusterSettings,
                                 tracer: Tracer): util.Map[String, Supplier[HttpServerTransport]] = {
    rorEsConfig
      .sslConfig
      .externalSsl
      .map(ssl =>
        "ssl_netty4" -> new Supplier[HttpServerTransport] {
          override def get(): HttpServerTransport = new SSLNetty4HttpServerTransport(settings, networkService, threadPool, xContentRegistry, dispatcher, ssl, clusterSettings, getSharedGroupFactory(settings), tracer, rorEsConfig.fipsConfig.isSslFipsCompliant)
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
    rorEsConfig
      .sslConfig
      .interNodeSsl
      .map(ssl =>
        "ror_ssl_internode" -> new Supplier[Transport] {
          override def get(): Transport = new SSLNetty4InternodeServerTransport(settings, threadPool, pageCacheRecycler, circuitBreakerService, namedWriteableRegistry, networkService, ssl, getSharedGroupFactory(settings), rorEsConfig.fipsConfig.isSslFipsCompliant)
        }
      )
      .toMap
      .asJava
  }

  private def getSharedGroupFactory(settings: Settings): SharedGroupFactory = {
    this.groupFactory.getOrElse(new SharedGroupFactory(settings))
      .ensuring(_.getSettings == settings, "Different settings than originally provided")
  }

  override def close(): Unit = {
    ilaf.stop()
  }

  override def getActions: util.List[ActionPlugin.ActionHandler[_ <: ActionRequest, _ <: ActionResponse]] = {
    List[ActionPlugin.ActionHandler[_ <: ActionRequest, _ <: ActionResponse]](
      new ActionHandler(RRAdminActionType.instance, classOf[TransportRRAdminAction]),
      new ActionHandler(RRAuthMockActionType.instance, classOf[TransportRRAuthMockAction]),
      new ActionHandler(RRTestConfigActionType.instance, classOf[TransportRRTestConfigAction]),
      new ActionHandler(RRConfigActionType.instance, classOf[TransportRRConfigAction]),
      new ActionHandler(RRUserMetadataActionType.instance, classOf[TransportRRUserMetadataAction]),
      new ActionHandler(RRAuditEventActionType.instance, classOf[TransportRRAuditEventAction]),
      // wrappers
      new ActionHandler(RorWrappedCatActionType.instance, classOf[TransportRorWrappedCatAction]),
      new ActionHandler(RorWrappedUpgradeActionType.instance, classOf[TransportRorWrappedUpgradeAction]),
    ).asJava
  }

  override def getRestHandlers(settings: Settings,
                               restController: RestController,
                               clusterSettings: ClusterSettings,
                               indexScopedSettings: IndexScopedSettings,
                               settingsFilter: SettingsFilter,
                               indexNameExpressionResolver: IndexNameExpressionResolver,
                               nodesInCluster: Supplier[DiscoveryNodes]): util.List[RestHandler] = {
    import tech.beshu.ror.es.utils.RestControllerOps.*
    restController.decorateRestHandlersWith(ChannelInterceptingRestHandlerDecorator.create)
    List[RestHandler](
      new RestRRAdminAction(),
      new RestRRAuthMockAction(),
      new RestRRTestConfigAction(),
      new RestRRConfigAction(nodesInCluster),
      new RestRRUserMetadataAction(),
      new RestRRAuditEventAction()
    ).asJava
  }

  override def getTransportInterceptors(namedWriteableRegistry: NamedWriteableRegistry,
                                        threadContext: ThreadContext): util.List[TransportInterceptor] = {
    List[TransportInterceptor](new RorTransportInterceptor(threadContext, s.get("node.name"))).asJava
  }

  override def onNodeStarted(): Unit = {
    super.onNodeStarted()
    doPrivileged {
      esInitListener.onEsReady()
    }
  }
}
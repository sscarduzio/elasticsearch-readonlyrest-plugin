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

import java.util
import java.util.function.Supplier

import monix.execution.Scheduler
import monix.execution.schedulers.CanBlock
import org.elasticsearch.ElasticsearchException
import org.elasticsearch.action.support.ActionFilter
import org.elasticsearch.action.{ActionRequest, ActionResponse}
import org.elasticsearch.common.component.LifecycleComponent
import org.elasticsearch.common.io.stream.NamedWriteableRegistry
import org.elasticsearch.common.network.NetworkService
import org.elasticsearch.common.settings._
import org.elasticsearch.common.util.BigArrays
import org.elasticsearch.env.Environment
import org.elasticsearch.http.HttpServerTransport
import org.elasticsearch.index.IndexModule
import org.elasticsearch.index.mapper.MapperService
import org.elasticsearch.indices.breaker.CircuitBreakerService
import org.elasticsearch.plugins.ActionPlugin.ActionHandler
import org.elasticsearch.plugins._
import org.elasticsearch.rest.RestHandler
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.transport.Transport
import tech.beshu.ror.Constants
import tech.beshu.ror.configuration.RorSsl
import tech.beshu.ror.es.dlsfls.RoleIndexSearcherWrapper
import tech.beshu.ror.es.rradmin.rest.RestRRAdminAction
import tech.beshu.ror.es.rradmin.{RRAdminAction, TransportRRAdminAction}
import tech.beshu.ror.es.ssl.{SSLNetty4HttpServerTransport, SSLNetty4InternodeServerTransport}
import tech.beshu.ror.providers.{EnvVarsProvider, OsEnvVarsProvider}
import tech.beshu.ror.buildinfo.LogPluginBuildInfoMessage

import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.language.postfixOps

class ReadonlyRestPlugin(s: Settings)
  extends Plugin
    with ScriptPlugin
    with ActionPlugin
    with IngestPlugin
    with NetworkPlugin {

  LogPluginBuildInfoMessage()

  Constants.FIELDS_ALWAYS_ALLOW.addAll(MapperService.getAllMetaFields.toList.asJava)

  private val environment = new Environment(s)
  implicit private val envVarsProvider: EnvVarsProvider = OsEnvVarsProvider
  private val timeout: FiniteDuration = 10 seconds
  private val sslConfig = RorSsl
    .load(environment.configFile)
    .map(_.fold(e => throw new ElasticsearchException(e.message), identity))
    .runSyncUnsafe(timeout)(Scheduler.global, CanBlock.permit)
  
  override def getGuiceServiceClasses: util.Collection[Class[_ <: LifecycleComponent]] = {
    List[Class[_ <: LifecycleComponent]](classOf[TransportServiceInterceptor]).asJava
  }

  override def getActionFilters: util.List[Class[_ <: ActionFilter]] = {
    List[Class[_ <: ActionFilter]](classOf[IndexLevelActionFilter]).asJava
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
                                 networkService: NetworkService): util.Map[String, Supplier[HttpServerTransport]] = {
    sslConfig
      .externalSsl
      .map(ssl =>
        "ssl_netty4" -> new Supplier[HttpServerTransport] {
          override def get(): HttpServerTransport = new SSLNetty4HttpServerTransport(settings, networkService, bigArrays, threadPool, ssl)
        }
      )
      .toMap
      .asJava
  }

  override def getTransports(settings: Settings,
                             threadPool: ThreadPool,
                             bigArrays: BigArrays,
                             circuitBreakerService: CircuitBreakerService,
                             namedWriteableRegistry: NamedWriteableRegistry,
                             networkService: NetworkService): util.Map[String, Supplier[Transport]] = {
    sslConfig
      .interNodeSsl
      .map(ssl =>
        "ror_ssl_internode" -> new Supplier[Transport] {
          override def get(): Transport = new SSLNetty4InternodeServerTransport(
            settings, threadPool, networkService, bigArrays, namedWriteableRegistry, circuitBreakerService, ssl
          )
        }
      )
      .toMap
      .asJava
  }

  override def getActions: util.List[ActionPlugin.ActionHandler[_ <: ActionRequest, _ <: ActionResponse]] = {
    List[ActionPlugin.ActionHandler[_ <: ActionRequest, _ <: ActionResponse]](
      new ActionHandler(RRAdminAction.instance, classOf[TransportRRAdminAction])
    ).asJava
  }

  override def getRestHandlers: util.List[Class[_ <: RestHandler]] = {
    List[Class[_ <: RestHandler]](classOf[ReadonlyRestAction], classOf[RestRRAdminAction]).asJava
  }

}
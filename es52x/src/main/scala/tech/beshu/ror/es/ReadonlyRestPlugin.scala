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
import java.util.Collections
import java.util.function.{Supplier, UnaryOperator}

import monix.execution.Scheduler
import monix.execution.schedulers.CanBlock
import org.elasticsearch.ElasticsearchException
import org.elasticsearch.action.support.ActionFilter
import org.elasticsearch.action.{ActionRequest, ActionResponse}
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.common.io.stream.NamedWriteableRegistry
import org.elasticsearch.common.network.NetworkService
import org.elasticsearch.common.settings._
import org.elasticsearch.common.util.BigArrays
import org.elasticsearch.common.util.concurrent.ThreadContext
import org.elasticsearch.common.xcontent.NamedXContentRegistry
import org.elasticsearch.env.Environment
import org.elasticsearch.http.HttpServerTransport
import org.elasticsearch.index.IndexModule
import org.elasticsearch.index.mapper.MapperService
import org.elasticsearch.indices.breaker.CircuitBreakerService
import org.elasticsearch.plugins.ActionPlugin.ActionHandler
import org.elasticsearch.plugins._
import org.elasticsearch.rest.{RestChannel, RestHandler, RestRequest}
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.transport.Transport
import tech.beshu.ror.Constants
import tech.beshu.ror.configuration.RorSsl
import tech.beshu.ror.es.dlsfls.RoleIndexSearcherWrapper
import tech.beshu.ror.es.rradmin.rest.RestRRAdminAction
import tech.beshu.ror.es.rradmin.{RRAdminAction, TransportRRAdminAction}
import tech.beshu.ror.es.ssl.{SSLNetty4HttpServerTransport, SSLNetty4InternodeServerTransport}
import tech.beshu.ror.es.utils.ThreadRepo

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

  LogBuildInfoMessage()

  Constants.FIELDS_ALWAYS_ALLOW.addAll(MapperService.getAllMetaFields.toList.asJava)

  private val environment = new Environment(s)
  private val timeout: FiniteDuration = 10 seconds
  private val sslConfig = RorSsl
    .load(environment.configFile)
    .map(_.fold(e => throw new ElasticsearchException(e.message), identity))
    .runSyncUnsafe(timeout)(Scheduler.global, CanBlock.permit)

  override def getActionFilters: util.List[Class[_ <: ActionFilter]] = {
    List(classOf[IndexLevelActionFilter].asInstanceOf).asJava
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
                                 networkService: NetworkService): util.Map[String, Supplier[HttpServerTransport]] = {
    sslConfig
      .externalSsl
      .map(ssl =>
        "ssl_netty4" -> new Supplier[HttpServerTransport] {
          override def get(): HttpServerTransport = new SSLNetty4HttpServerTransport(settings, networkService, bigArrays, threadPool, xContentRegistry, ssl)
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
    Collections.singletonList(classOf[RestRRAdminAction])
  }

  override def getRestHandlerWrapper(threadContext: ThreadContext): UnaryOperator[RestHandler] = {
    restHandler: RestHandler =>
      (request: RestRequest, channel: RestChannel, client: NodeClient) => {
        ThreadRepo.setRestChannel(channel)
        restHandler.handleRequest(request, channel, client)
      }
  }
}
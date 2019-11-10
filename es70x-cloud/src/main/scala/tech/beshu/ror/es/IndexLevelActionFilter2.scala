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

import java.util.function.Supplier

import monix.execution.Scheduler.Implicits.global
import monix.execution.atomic.Atomic
import org.apache.http.HttpHost
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.search.SearchRequest
import org.elasticsearch.action.support.{ActionFilter, ActionFilterChain}
import org.elasticsearch.action.{ActionListener, ActionRequest, ActionResponse}
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.client.{RequestOptions, RestClient, RestHighLevelClient}
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.common.xcontent.ToXContent
import org.elasticsearch.env.Environment
import org.elasticsearch.rest.{BytesRestResponse, RestChannel}
import org.elasticsearch.tasks.Task
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.transport.RemoteClusterService
import tech.beshu.ror.SecurityPermissionException
import tech.beshu.ror.accesscontrol.domain.UriPath.CurrentUserMetadataPath
import tech.beshu.ror.accesscontrol.request.EsRequestContext
import tech.beshu.ror.boot.{Engine, Ror, RorInstance}
import tech.beshu.ror.es.providers.{EsAuditSink, EsIndexJsonContentProvider, EsRestClientBasedRorClusterService}
import tech.beshu.ror.es.request.RorNotAvailableResponse.createRorNotReadyYetResponse
import tech.beshu.ror.es.request.regular.RegularRequestHandler
import tech.beshu.ror.es.request.usermetadata.CurrentUserMetadataRequestHandler
import tech.beshu.ror.es.request.{RequestInfo, RorNotAvailableResponse}
import tech.beshu.ror.es.utils.AccessControllerHelper._
import tech.beshu.ror.es.utils.ThreadRepo2

import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

class IndexLevelActionFilter2(clusterService: ClusterService,
                              client: NodeClient,
                              threadPool: ThreadPool,
                              env: Environment,
                              remoteClusterServiceSupplier: Supplier[Option[RemoteClusterService]])
  extends ActionFilter with Logging {

  private val rorInstance: Atomic[Option[RorInstance]] = Atomic(Option.empty[RorInstance])
  private val esClient = new RestHighLevelClient(RestClient.builder(HttpHost.create("http://localhost:9200")))
  private val rorClusterService = new EsRestClientBasedRorClusterService(esClient)

  private val startingTaskCancellable = Ror
    .start(env.configFile, new EsAuditSink(client), new EsIndexJsonContentProvider(client))
    .runAsync {
      case Right(Right(instance)) =>
        System.out.println("ROR CORE started - proxy fully functional")
        RorInstanceSupplier.update(instance)
        rorInstance.set(Some(instance))
      case Right(Left(failure)) =>
        throw StartingFailureException.from(failure)
      case Left(ex) =>
        throw StartingFailureException.from(ex)
    }

  override def order(): Int = 0

  def stop(): Unit = {
    startingTaskCancellable.cancel()
    rorInstance.get().map(_.stop())
    esClient.close()
  }

  override def apply[Request <: ActionRequest, Response <: ActionResponse](task: Task,
                                                                           action: String,
                                                                           request: Request,
                                                                           listener: ActionListener[Response],
                                                                           chain: ActionFilterChain[Request, Response]): Unit = {
    doPrivileged {
      (rorInstance.get().flatMap(_.engine), ThreadRepo2.getRestChannel) match {
        case (_, None) => chain.proceed(task, action, request, listener)
        case (_, _) if action.startsWith("internal:") => chain.proceed(task, action, request, listener)
        case (None, Some(channel)) => channel.sendResponse(createRorNotReadyYetResponse(channel))
        case (Some(engine), Some(channel)) =>
          handleRequest(
            engine,
            task,
            action,
            request,
            listener.asInstanceOf[ActionListener[ActionResponse]],
            chain.asInstanceOf[ActionFilterChain[ActionRequest, ActionResponse]],
            channel
          )
      }
    }
  }

  private def handleRequest(engine: Engine,
                            task: Task,
                            action: String,
                            request: ActionRequest,
                            listener: ActionListener[ActionResponse],
                            chain: ActionFilterChain[ActionRequest, ActionResponse],
                            channel: RestChannel): Unit = {
    val requestInfo = new RequestInfo(channel, task.getId, action, request, rorClusterService, threadPool, None)
    val requestContext = requestContextFrom(requestInfo)
    requestContext.uriPath match {
      case CurrentUserMetadataPath(_) =>
        val handler = new CurrentUserMetadataRequestHandler(engine, task, action, request, listener, chain, channel, threadPool)
        handler.handle(requestInfo, requestContext)
      case _ =>
        val newChain = new ActionFilterChain[ActionRequest, ActionResponse] {
          override def proceed(task: Task, action: String, request: ActionRequest, listener: ActionListener[ActionResponse]): Unit = {
            request match {
              case sr: SearchRequest =>
                Try(esClient.search(sr, RequestOptions.DEFAULT)) match {
                  case Failure(exception) =>
                    channel.sendResponse(RorNotAvailableResponse.createRorNotEnabledResponse(channel))
                  case Success(result) =>
                    channel.sendResponse(new BytesRestResponse(
                      result.status(),
                      result.toXContent(channel.newBuilder(), ToXContent.EMPTY_PARAMS)
                    ))
                }
              case _ =>
            }
          }
        }
        val handler = new RegularRequestHandler(engine, task, action, request, listener, newChain, channel, threadPool)
        handler.handle(requestInfo, requestContext)
    }
  }

  private def requestContextFrom(requestInfo: RequestInfo) =
    EsRequestContext
      .from(requestInfo)
      .fold(
        ex => throw new SecurityPermissionException("Cannot create request context object", ex),
        identity
      )
}

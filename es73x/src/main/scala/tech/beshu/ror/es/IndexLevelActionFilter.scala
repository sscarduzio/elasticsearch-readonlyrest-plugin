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

import monix.execution.Scheduler.Implicits.global
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.search.{MultiSearchRequest, SearchRequest}
import org.elasticsearch.action.support.{ActionFilter, ActionFilterChain}
import org.elasticsearch.action.{ActionListener, ActionRequest, ActionResponse}
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.env.Environment
import org.elasticsearch.rest.RestChannel
import org.elasticsearch.tasks.Task
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.transport.RemoteClusterService
import tech.beshu.ror.SecurityPermissionException
import tech.beshu.ror.accesscontrol.AccessControl.{RegularRequestResult, Result}
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.request.{EsRequestContext, RequestContext}
import tech.beshu.ror.accesscontrol.{AccessControlResultCommitter, AccessControlStaticContext, AclActionHandler, BlockContextJavaHelper}
import tech.beshu.ror.boot.{Engine, Ror}
import tech.beshu.ror.es.utils.AccessControllerHelper._
import tech.beshu.ror.es.utils.ThreadRepo

import scala.concurrent.duration._
import scala.collection.JavaConverters._
import scala.language.postfixOps
import scala.util.{Either, Try}

class IndexLevelActionFilter(clusterService: ClusterService,
                             client: NodeClient,
                             threadPool: ThreadPool,
                             env: Environment,
                             remoteClusterServiceSupplier: Supplier[Option[RemoteClusterService]])
  extends ActionFilter with Logging {

  private val rorInstance = {
    val startingResult = Ror
      .start(env.configFile, new EsAuditSink(client), new EsIndexJsonContentProvider(client))
      .runSyncUnsafe(1 minute)
    startingResult match {
      case Right(instance) =>
        RorInstanceSupplier.update(instance)
        instance
      case Left(ex) =>
        throw StartingFailureException.from(ex)
    }
  }

  override def order(): Int = 0

  def stop(): Unit = rorInstance.stop()

  override def apply[Request <: ActionRequest, Response <: ActionResponse](task: Task,
                                                                           action: String,
                                                                           request: Request,
                                                                           listener: ActionListener[Response],
                                                                           chain: ActionFilterChain[Request, Response]): Unit = {
    doPrivileged {
      (rorInstance.engine, ThreadRepo.getRestChannel) match {
        case (_, None) => chain.proceed(task, action, request, listener)
        case (_, _) if action.startsWith("internal:") => chain.proceed(task, action, request, listener)
        case (None, Some(channel)) => channel.sendResponse(RorNotReadyResponse.create(channel))
        case (Some(engine), Some(channel)) => handleRequest(engine, task, action, request, listener, chain, channel)
      }
    }
  }

  private def handleRequest[Request <: ActionRequest, Response <: ActionResponse](engine: Engine,
                                                                                  task: Task,
                                                                                  action: String,
                                                                                  request: Request,
                                                                                  listener: ActionListener[Response],
                                                                                  chain: ActionFilterChain[Request, Response],
                                                                                  channel: RestChannel) = {
    remoteClusterServiceSupplier.get() match {
      case Some(remoteClusterService) =>
        val requestInfo = new RequestInfo(channel, task.getId, action, request, clusterService, threadPool, remoteClusterService)
        val requestContext = requestContextFrom(requestInfo)
        val proceed = (responseActionListener: ActionListener[Response]) => chain.proceed(task, action, request, responseActionListener)

        engine.acl
          .handleRegularRequest(requestContext)
          .runAsync(handleAclResult(engine, listener, request, requestContext, requestInfo, proceed, channel))
      case None =>
        listener.onFailure(new Exception("Cluster service not ready yet. Cannot continue"))
    }
  }

  private def handleAclResult[Request <: ActionRequest, Response <: ActionResponse](engine: Engine,
                                                                                    listener: ActionListener[Response],
                                                                                    request: Request,
                                                                                    requestContext: RequestContext,
                                                                                    requestInfo: RequestInfo,
                                                                                    proceed: ActionListener[Response] => Unit,
                                                                                    channel: RestChannel) = {
    result: Either[Throwable, Result[RegularRequestResult]] => {
      import tech.beshu.ror.utils.ScalaOps._
      threadPool.getThreadContext.stashContext.bracket { _ =>
        result match {
          case Right(r) =>
            val handler = createAclActionHandler(engine.context, requestInfo, request, requestContext, listener, proceed, channel)
            AccessControlResultCommitter.commit(r.handlingResult, handler)
          case Left(ex) =>
            listener.onFailure(new Exception(ex))
        }
      }
    }
  }

  private def createAclActionHandler[Request <: ActionRequest, Response <: ActionResponse](aclStaticContext: AccessControlStaticContext,
                                                                                           requestInfo: RequestInfo,
                                                                                           request: Request,
                                                                                           requestContext: RequestContext,
                                                                                           baseListener: ActionListener[Response],
                                                                                           proceed: ActionListener[Response] => Unit,
                                                                                           channel: RestChannel) = {
    new AclActionHandler {
      override def onAllow(blockContext: BlockContext): Unit = {
        val searchListener = createSearchListener(baseListener, request, requestContext, blockContext, aclStaticContext)
        requestInfo.writeResponseHeaders(BlockContextJavaHelper.responseHeadersFrom(blockContext).asJava)
        requestInfo.writeToThreadContextHeaders(BlockContextJavaHelper.contextHeadersFrom(blockContext).asJava)
        requestInfo.writeIndices(BlockContextJavaHelper.indicesFrom(blockContext).asJava)
        requestInfo.writeSnapshots(BlockContextJavaHelper.snapshotsFrom(blockContext).asJava)
        requestInfo.writeRepositories(BlockContextJavaHelper.repositoriesFrom(blockContext).asJava)

        proceed(searchListener)
      }

      override def onForbidden(causes: util.List[AclActionHandler.ForbiddenCause]): Unit =
        channel.sendResponse(ForbiddenResponse.create(channel, causes.asScala.toList, aclStaticContext))

      override def onError(t: Throwable): Unit = baseListener.onFailure(t.asInstanceOf[Exception])

      override def onPassThrough(): Unit = proceed(baseListener)
    }
  }

  private def createSearchListener[Request <: ActionRequest, Response <: ActionResponse](listener: ActionListener[Response],
                                                                                         request: Request,
                                                                                         requestContext: RequestContext,
                                                                                         blockContext: BlockContext,
                                                                                         aclStaticContext: AccessControlStaticContext) = {
    Try {
      // Cache disabling for those 2 kind of request is crucial for
      // document level security to work. Otherwise we'd get an answer from
      // the cache some times and would not be filtered
      if (aclStaticContext.involvesFilter) {
        request match {
          case r: SearchRequest =>
            logger.debug("ACL involves filters, will disable request cache for SearchRequest")
            r.requestCache(false)
          case r: MultiSearchRequest =>
            logger.debug("ACL involves filters, will disable request cache for MultiSearchRequest")
            r.requests().asScala.foreach(_.requestCache(false))
          case _ =>
        }
      }
      new ResponseActionListener(listener.asInstanceOf[ActionListener[ActionResponse]], requestContext, blockContext).asInstanceOf[ActionListener[Response]]
    } fold(
      e => {
        logger.error("on allow exception", e)
        listener
      },
      identity
    )
  }

  private def requestContextFrom(requestInfo: RequestInfo) =
    EsRequestContext
      .from(requestInfo)
      .fold(
        ex => throw new SecurityPermissionException("Cannot create request context object", ex),
        identity
      )
}

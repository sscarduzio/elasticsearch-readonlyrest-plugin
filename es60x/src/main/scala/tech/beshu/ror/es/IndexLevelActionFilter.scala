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

import monix.execution.Scheduler.Implicits.global
import monix.execution.atomic.Atomic
import org.elasticsearch.Version
import org.elasticsearch.action.admin.cluster.state.ClusterStateResponse
import org.elasticsearch.action.support.{ActionFilter, ActionFilterChain}
import org.elasticsearch.action.{ActionListener, ActionRequest, ActionResponse}
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.cluster.{ClusterName, ClusterState}
import org.elasticsearch.common.component.AbstractComponent
import org.elasticsearch.common.inject.Inject
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.discovery.zen.PublishClusterStateAction.serializeFullClusterState
import org.elasticsearch.env.Environment
import org.elasticsearch.rest.RestChannel
import org.elasticsearch.tasks.Task
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.SecurityPermissionException
import tech.beshu.ror.accesscontrol.domain.UriPath.CurrentUserMetadataPath
import tech.beshu.ror.accesscontrol.request.EsRequestContext
import tech.beshu.ror.boot.{Engine, Ror, RorInstance}
import tech.beshu.ror.es.providers.{EsAuditSink, EsIndexJsonContentProvider}
import tech.beshu.ror.es.request.RequestInfo
import tech.beshu.ror.es.request.RorNotAvailableResponse.{createRorNotReadyYetResponse, createRorStartingFailureResponse}
import tech.beshu.ror.es.request.regular.RegularRequestHandler
import tech.beshu.ror.es.request.usermetadata.CurrentUserMetadataRequestHandler
import tech.beshu.ror.es.utils.AccessControllerHelper._
import tech.beshu.ror.es.utils.ThreadRepo
import tech.beshu.ror.providers.{EnvVarsProvider, OsEnvVarsProvider}

import scala.language.postfixOps

class IndexLevelActionFilter(settings: Settings,
                             clusterService: ClusterService,
                             client: NodeClient,
                             threadPool: ThreadPool,
                             env: Environment,
                             ignore: Unit)
  extends AbstractComponent(settings) with ActionFilter {

  @Inject
  def this(settings: Settings,
           clusterService: ClusterService,
           client: NodeClient,
           threadPool: ThreadPool,
           env: Environment) {
    this(settings, clusterService, client, threadPool, env, ())
  }

  private val emptyClusterState = new ClusterStateResponse(
    ClusterName.CLUSTER_NAME_SETTING.get(settings),
    ClusterState.EMPTY_STATE,
    serializeFullClusterState(ClusterState.EMPTY_STATE, Version.CURRENT).length
  )
  private val rorInstanceState: Atomic[RorInstanceStartingState] =
    Atomic(RorInstanceStartingState.Starting: RorInstanceStartingState)
  implicit private val envVarsProvider: EnvVarsProvider = OsEnvVarsProvider

  private val startingTaskCancellable = doPrivileged {
    Ror
      .start(env.configFile, new EsAuditSink(client), new EsIndexJsonContentProvider(client))
      .runAsync {
        case Right(Right(instance)) =>
          RorInstanceSupplier.update(instance)
          rorInstanceState.set(RorInstanceStartingState.Started(instance))
        case Right(Left(failure)) =>
          val startingFailureException = StartingFailureException.from(failure)
          logger.error("ROR starting failure:", startingFailureException)
          rorInstanceState.set(RorInstanceStartingState.NotStarted(startingFailureException))
        case Left(ex) =>
          val startingFailureException = StartingFailureException.from(ex)
          logger.error("ROR starting failure:", startingFailureException)
          rorInstanceState.set(RorInstanceStartingState.NotStarted(StartingFailureException.from(startingFailureException)))
      }
  }

  override def order(): Int = 0

  def stop(): Unit = {
    startingTaskCancellable.cancel()
    rorInstanceState.get() match {
      case RorInstanceStartingState.Starting =>
      case RorInstanceStartingState.Started(instance) => instance.stop().runSyncUnsafe()
      case RorInstanceStartingState.NotStarted(_) =>
    }
  }

  override def apply[Request <: ActionRequest, Response <: ActionResponse](task: Task,
                                                                           action: String,
                                                                           request: Request,
                                                                           listener: ActionListener[Response],
                                                                           chain: ActionFilterChain[Request, Response]): Unit = {
    doPrivileged {
      ThreadRepo.getRestChannel match {
        case None =>
          chain.proceed(task, action, request, listener)
        case Some(_) if action.startsWith("internal:") =>
          chain.proceed(task, action, request, listener)
        case Some(channel) =>
          rorInstanceState.get() match {
            case RorInstanceStartingState.Starting =>
              channel.sendResponse(createRorNotReadyYetResponse(channel))
            case RorInstanceStartingState.Started(instance) =>
              instance.engine match {
                case Some(engine) =>
                  handleRequest(
                    engine,
                    task,
                    action,
                    request,
                    listener.asInstanceOf[ActionListener[ActionResponse]],
                    chain.asInstanceOf[ActionFilterChain[ActionRequest, ActionResponse]],
                    channel
                  )
                case None =>
                  channel.sendResponse(createRorNotReadyYetResponse(channel))
              }
            case RorInstanceStartingState.NotStarted(_) =>
              channel.sendResponse(createRorStartingFailureResponse(channel))
          }
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
    TransportServiceInterceptor.remoteClusterServiceSupplier.get() match {
      case Some(remoteClusterService) =>
        val requestInfo = new RequestInfo(channel, task.getId, action, request, clusterService, threadPool, remoteClusterService)
        val requestContext = requestContextFrom(requestInfo)
        requestContext.uriPath match {
          case CurrentUserMetadataPath(_) =>
            val handler = new CurrentUserMetadataRequestHandler(engine, task, action, request, listener, chain, channel, threadPool)
            handler.handle(requestInfo, requestContext)
          case _ =>
            val handler = new RegularRequestHandler(engine, task, action, request, listener, chain, channel, threadPool, emptyClusterState)
            handler.handle(requestInfo, requestContext)
        }
      case None =>
        listener.onFailure(new Exception("Cluster service not ready yet. Cannot continue"))
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

private sealed trait RorInstanceStartingState
private object RorInstanceStartingState {
  case object Starting extends RorInstanceStartingState
  final case class Started(instance: RorInstance) extends RorInstanceStartingState
  final case class NotStarted(cause: StartingFailureException) extends RorInstanceStartingState
}
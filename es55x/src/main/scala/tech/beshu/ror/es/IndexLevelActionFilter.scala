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
import org.elasticsearch.action.support.{ActionFilter, ActionFilterChain}
import org.elasticsearch.action.{ActionListener, ActionRequest, ActionResponse}
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.common.component.AbstractComponent
import org.elasticsearch.common.inject.{Inject, Singleton}
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.env.Environment
import org.elasticsearch.rest.RestChannel
import org.elasticsearch.tasks.Task
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.boot.{Engine, Ror, RorInstance}
import tech.beshu.ror.es.request.AclAwareRequestFilter
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.RorNotAvailableResponse.{createRorNotReadyYetResponse, createRorStartingFailureResponse}
import tech.beshu.ror.es.services.{EsAuditSinkService, EsIndexJsonContentService, EsServerBasedRorClusterService}
import tech.beshu.ror.es.utils.ThreadRepo
import tech.beshu.ror.exceptions.StartingFailureException
import tech.beshu.ror.providers.{EnvVarsProvider, OsEnvVarsProvider}
import tech.beshu.ror.utils.AccessControllerHelper._
import tech.beshu.ror.utils.RorInstanceSupplier

import scala.language.postfixOps

@Singleton
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

  private implicit val envVarsProvider: EnvVarsProvider = OsEnvVarsProvider

  private val rorInstanceState: Atomic[RorInstanceStartingState] =
    Atomic(RorInstanceStartingState.Starting: RorInstanceStartingState)

  private val aclAwareRequestFilter = new AclAwareRequestFilter(
    new EsServerBasedRorClusterService(clusterService, client), threadPool
  )

  private val startingTaskCancellable = startRorInstance()

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
      ThreadRepo.getRorRestChannelFor(task) match {
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
        aclAwareRequestFilter
          .handle(
            engine,
            EsContext(channel, task, action, request, listener, chain, remoteClusterService.isCrossClusterSearchEnabled, engine.context.involvesFields)
          )
          .runAsync {
            case Right(_) =>
            case Left(ex) => listener.onFailure(new Exception(ex))
          }
      case None =>
        listener.onFailure(new Exception("Cluster service not ready yet. Cannot continue"))
    }
  }

  private def startRorInstance() = doPrivileged {
    new Ror()
      .start(env.configFile, new EsAuditSinkService(client), new EsIndexJsonContentService(client))
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
}

private sealed trait RorInstanceStartingState
private object RorInstanceStartingState {
  case object Starting extends RorInstanceStartingState
  final case class Started(instance: RorInstance) extends RorInstanceStartingState
  final case class NotStarted(cause: StartingFailureException) extends RorInstanceStartingState
}
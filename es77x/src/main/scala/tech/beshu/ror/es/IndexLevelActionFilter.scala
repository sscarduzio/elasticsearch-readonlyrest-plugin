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

import monix.execution.atomic.Atomic
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.support.{ActionFilter, ActionFilterChain}
import org.elasticsearch.action.{ActionListener, ActionRequest, ActionResponse}
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.env.Environment
import org.elasticsearch.tasks.Task
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.transport.RemoteClusterService
import tech.beshu.ror.boot.{Engine, EsInitListener, Ror, RorInstance}
import tech.beshu.ror.es.request.AclAwareRequestFilter
import tech.beshu.ror.es.request.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.request.RorNotAvailableResponse._
import tech.beshu.ror.es.services.{EsAuditSinkService, EsIndexJsonContentService, EsServerBasedRorClusterService}
import tech.beshu.ror.es.utils.ThreadRepo
import tech.beshu.ror.exceptions.StartingFailureException
import tech.beshu.ror.providers.{EnvVarsProvider, OsEnvVarsProvider}
import tech.beshu.ror.utils.AccessControllerHelper._
import tech.beshu.ror.utils.RorInstanceSupplier
import tech.beshu.ror.boot.RorSchedulers.Implicits.mainScheduler

import scala.language.postfixOps

class IndexLevelActionFilter(clusterService: ClusterService,
                             client: NodeClient,
                             threadPool: ThreadPool,
                             env: Environment,
                             remoteClusterServiceSupplier: Supplier[Option[RemoteClusterService]],
                             esInitListener: EsInitListener)
  extends ActionFilter with Logging {

  implicit private val envVarsProvider: EnvVarsProvider = OsEnvVarsProvider

  private val rorInstanceState: Atomic[RorInstanceStartingState] =
    Atomic(RorInstanceStartingState.Starting: RorInstanceStartingState)

  private val aclAwareRequestFilter = new AclAwareRequestFilter(
    new EsServerBasedRorClusterService(clusterService, client),
    threadPool
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
                            channel: RorRestChannel): Unit = {
    remoteClusterServiceSupplier.get() match {
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

  private def startRorInstance() = {
    val startResult = for {
      _ <- esInitListener.waitUntilReady
      result <- new Ror().start(env.configFile, new EsAuditSinkService(client), new EsIndexJsonContentService(client))
    } yield result
    startResult.runAsync {
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
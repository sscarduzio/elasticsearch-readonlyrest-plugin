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

import monix.execution.atomic.Atomic
import org.elasticsearch.action.support.{ActionFilter, ActionFilterChain}
import org.elasticsearch.action.{ActionListener, ActionRequest, ActionResponse}
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.common.component.AbstractComponent
import org.elasticsearch.common.inject.{Inject, Singleton}
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.env.Environment
import org.elasticsearch.tasks.Task
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.domain.AuditCluster
import tech.beshu.ror.accesscontrol.matchers.{RandomBasedUniqueIdentifierGenerator, UniqueIdentifierGenerator}
import tech.beshu.ror.boot.ReadonlyRest.AuditSinkCreator
import tech.beshu.ror.boot.RorSchedulers.Implicits.mainScheduler
import tech.beshu.ror.boot.engines.Engines
import tech.beshu.ror.boot.{Ror, RorInstance, RorMode}
import tech.beshu.ror.es.handler.AclAwareRequestFilter
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.response.ForbiddenResponse.{createRorNotReadyYetResponse, createRorStartingFailureResponse}
import tech.beshu.ror.es.services.{EsAuditSinkService, EsIndexJsonContentService, EsServerBasedRorClusterService, HighLevelClientAuditSinkService}
import tech.beshu.ror.es.utils.ThreadRepo
import tech.beshu.ror.exceptions.StartingFailureException
import tech.beshu.ror.providers.{EnvVarsProvider, OsEnvVarsProvider}
import tech.beshu.ror.utils.AccessControllerHelper._
import tech.beshu.ror.utils.{JavaConverters, RorInstanceSupplier}

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
  private implicit val generator: UniqueIdentifierGenerator = RandomBasedUniqueIdentifierGenerator

  private val rorInstanceState: Atomic[RorInstanceStartingState] =
    Atomic(RorInstanceStartingState.Starting: RorInstanceStartingState)

  private val aclAwareRequestFilter = new AclAwareRequestFilter(
    new EsServerBasedRorClusterService(
      settings,
      clusterService,
      TransportServiceInterceptor.remoteClusterServiceSupplier,
      SnapshotsServiceInterceptor.snapshotsServiceSupplier,
      client,
      threadPool
    ),
    settings,
    client,
    threadPool
  )

  private val startingTaskCancellable = startRorInstance()

  private val auditSinkCreator: AuditSinkCreator = {
    case AuditCluster.LocalAuditCluster =>
      new EsAuditSinkService(client)
    case remote: AuditCluster.RemoteAuditCluster =>
      HighLevelClientAuditSinkService.create(remote)
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
      ThreadRepo.getRorRestChannel match {
        case None =>
          chain.proceed(task, action, request, listener)
        case Some(_) if action.startsWith("internal:") =>
          chain.proceed(task, action, request, listener)
        case Some(channel) =>
          proceedByRorEngine(
            EsContext(
              channel,
              task,
              action,
              request,
              listener.asInstanceOf[ActionListener[ActionResponse]],
              chain.asInstanceOf[ActionFilterChain[ActionRequest, ActionResponse]],
              JavaConverters.flattenPair(threadPool.getThreadContext.getResponseHeaders).toSet
            )
          )
      }
    }
  }

  private def proceedByRorEngine(esContext: EsContext): Unit = {
    rorInstanceState.get() match {
      case RorInstanceStartingState.Starting =>
        logger.warn(s"[${esContext.requestContextId}] Cannot handle the request ${esContext.channel.request().path()} because ReadonlyREST hasn't started yet")
        esContext.listener.onFailure(createRorNotReadyYetResponse())
      case RorInstanceStartingState.Started(instance) =>
        instance.engines match {
          case Some(engines) =>
            handleRequest(engines, esContext)
          case None =>
            logger.warn(s"[${esContext.requestContextId}] Cannot handle the request ${esContext.channel.request().path()} because ReadonlyREST hasn't started yet")
            esContext.listener.onFailure(createRorNotReadyYetResponse())
        }
      case RorInstanceStartingState.NotStarted(_) =>
        logger.error(s"[${esContext.requestContextId}] Cannot handle the ${esContext.channel.request().path()} request because ReadonlyREST failed to start")
        esContext.listener.onFailure(createRorStartingFailureResponse())
    }
  }

  private def handleRequest(engines: Engines, esContext: EsContext): Unit = {
    aclAwareRequestFilter
      .handle(engines, esContext)
      .runAsync {
        case Right(_) =>
        case Left(ex) => esContext.listener.onFailure(new Exception(ex))
      }
  }

  private def startRorInstance() = {
    val startResult = for {
      _ <- EsInitListenerSingleton.waitUntilReady
      result <- new Ror(RorMode.Plugin, auditSinkCreator).start(env.configFile, new EsIndexJsonContentService(client))
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
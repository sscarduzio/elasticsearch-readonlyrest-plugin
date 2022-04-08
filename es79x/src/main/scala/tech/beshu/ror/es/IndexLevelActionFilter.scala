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
import org.apache.logging.log4j.scala.Logging
import org.elasticsearch.action.support.{ActionFilter, ActionFilterChain}
import org.elasticsearch.action.{ActionListener, ActionRequest, ActionResponse}
import org.elasticsearch.client.node.NodeClient
import org.elasticsearch.cluster.service.ClusterService
import org.elasticsearch.env.Environment
import org.elasticsearch.repositories.RepositoriesService
import org.elasticsearch.tasks.Task
import org.elasticsearch.threadpool.ThreadPool
import org.elasticsearch.transport.RemoteClusterService
import tech.beshu.ror.accesscontrol.domain.AuditCluster
import tech.beshu.ror.accesscontrol.matchers.UniqueIdentifierGenerator
import tech.beshu.ror.boot.ReadonlyRest.{AuditSinkCreator, RorMode}
import tech.beshu.ror.boot.RorSchedulers.Implicits.mainScheduler
import tech.beshu.ror.boot._
import tech.beshu.ror.boot.engines.Engines
import tech.beshu.ror.es.handler.AclAwareRequestFilter
import tech.beshu.ror.es.handler.AclAwareRequestFilter.{EsChain, EsContext}
import tech.beshu.ror.es.handler.response.ForbiddenResponse.{createRorNotReadyYetResponse, createRorStartingFailureResponse, createTestSettingsNotConfiguredResponse}
import tech.beshu.ror.es.services.{EsAuditSinkService, EsIndexJsonContentService, EsServerBasedRorClusterService, HighLevelClientAuditSinkService}
import tech.beshu.ror.es.utils.ThreadRepo
import tech.beshu.ror.exceptions.StartingFailureException
import tech.beshu.ror.providers.{EnvVarsProvider, PropertiesProvider}
import tech.beshu.ror.utils.AccessControllerHelper._
import tech.beshu.ror.utils.{JavaConverters, RorInstanceSupplier}

import java.time.Clock
import java.util.function.Supplier
import scala.language.postfixOps

class IndexLevelActionFilter(nodeName: String,
                             clusterService: ClusterService,
                             client: NodeClient,
                             threadPool: ThreadPool,
                             env: Environment,
                             remoteClusterServiceSupplier: Supplier[Option[RemoteClusterService]],
                             repositoriesServiceSupplier: Supplier[Option[RepositoriesService]],
                             esInitListener: EsInitListener)
                            (implicit envVarsProvider: EnvVarsProvider,
                             propertiesProvider: PropertiesProvider,
                             generator: UniqueIdentifierGenerator)
  extends ActionFilter with Logging {

  private implicit val clock: Clock = Clock.systemUTC()

  private val ror = ReadonlyRest.create(
    RorMode.Plugin,
    new EsIndexJsonContentService(client),
    auditSinkCreator,
    env.configFile
  )

  private val rorInstanceState: Atomic[RorInstanceStartingState] =
    Atomic(RorInstanceStartingState.Starting: RorInstanceStartingState)

  private val aclAwareRequestFilter = new AclAwareRequestFilter(
    new EsServerBasedRorClusterService(
      clusterService,
      remoteClusterServiceSupplier,
      repositoriesServiceSupplier,
      client,
      threadPool
    ),
    clusterService.getSettings,
    threadPool
  )

  private val startingTaskCancellable = startRorInstance()

  private def auditSinkCreator: AuditSinkCreator = {
    case AuditCluster.LocalAuditCluster =>
      new EsAuditSinkService(client, threadPool)
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
      proceed(
        task,
        action,
        request,
        listener.asInstanceOf[ActionListener[ActionResponse]],
        new EsChain(
          chain.asInstanceOf[ActionFilterChain[ActionRequest, ActionResponse]],
          threadPool
        )
      )
    }
  }

  private def proceed(task: Task,
                      action: String,
                      request: ActionRequest,
                      listener: ActionListener[ActionResponse],
                      chain: EsChain): Unit = {
    ThreadRepo.getRorRestChannel match {
      case None =>
        chain.continue(nodeName, task, action, request, listener)
      case Some(_) if action.startsWith("internal:") =>
        chain.continue(nodeName, task, action, request, listener)
      case Some(channel) =>
        proceedByRorEngine(
          EsContext(
            channel,
            nodeName,
            task,
            action,
            request,
            listener,
            chain,
            JavaConverters.flattenPair(threadPool.getThreadContext.getResponseHeaders).toSet
          )
        )
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
        case Right(result) => handleResult(esContext, result)
        case Left(ex) => esContext.listener.onFailure(new Exception(ex))
      }
  }

  private def handleResult(esContext: EsContext, result: Either[AclAwareRequestFilter.Error, Unit]): Unit = result match {
    case Right(_) =>
    case Left(AclAwareRequestFilter.Error.TestEngineNotConfigured) =>
      esContext.listener.onFailure(createTestSettingsNotConfiguredResponse())
  }

  private def startRorInstance() = {
    val startResult = for {
      _ <- esInitListener.waitUntilReady
      result <- ror.start()
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
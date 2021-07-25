/*
 *     Beshu Limited all rights reserved
 */
package tech.beshu.ror.proxy.es

import java.nio.file.Path

import cats.data.EitherT
import monix.eval.{Task => MTask}
import monix.execution.Scheduler
import org.elasticsearch.action.support.{ActionFilter, ActionFilterChain}
import org.elasticsearch.action.{ActionListener, ActionRequest, ActionResponse}
import org.elasticsearch.common.settings.Settings
import org.elasticsearch.tasks.Task
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.accesscontrol.matchers.UniqueIdentifierGenerator
import tech.beshu.ror.boot.{Engine, Ror, RorInstance, RorMode, StartingFailure}
import tech.beshu.ror.es.handler.AclAwareRequestFilter
import tech.beshu.ror.es.handler.AclAwareRequestFilter.EsContext
import tech.beshu.ror.es.handler.response.RorNotAvailableResponse.createRorNotReadyYetResponse
import tech.beshu.ror.exceptions.SecurityPermissionException
import tech.beshu.ror.providers.EnvVarsProvider
import tech.beshu.ror.proxy.es.ProxyIndexLevelActionFilter.ThreadRepoChannelRenewalOnChainProceed
import tech.beshu.ror.proxy.es.clients.{ProxyFilterable, RestHighLevelClientAdapter}
import tech.beshu.ror.proxy.es.services.{EsRestClientBasedRorClusterService, ProxyAuditSinkService, ProxyIndexJsonContentService}
import tech.beshu.ror.utils.RorInstanceSupplier

import scala.util.{Failure, Success, Try}

class ProxyIndexLevelActionFilter private(rorInstance: RorInstance,
                                          esClient: RestHighLevelClientAdapter,
                                          threadPool: ThreadPool)
                                         (implicit override val scheduler: Scheduler,
                                          generator: UniqueIdentifierGenerator)
  extends ActionFilter
    with ProxyFilterable {

  override val proxyFilter: ProxyIndexLevelActionFilter = this

  private val aclAwareRequestFilter = new AclAwareRequestFilter(
    new EsRestClientBasedRorClusterService(esClient), Settings.EMPTY, threadPool
  )

  override def order(): Int = 0

  override def apply[Request <: ActionRequest, Response <: ActionResponse](task: Task,
                                                                           action: String,
                                                                           request: Request,
                                                                           listener: ActionListener[Response],
                                                                           chain: ActionFilterChain[Request, Response]): Unit = {
    (rorInstance.engine, ProxyThreadRepo.getRestChannel(task)) match {
      case (Some(engine), Some(channel)) =>
        Try {
          handleRequest(
            engine,
            task,
            action,
            request,
            listener.asInstanceOf[ActionListener[ActionResponse]],
            new ThreadRepoChannelRenewalOnChainProceed(chain, channel).asInstanceOf[ActionFilterChain[ActionRequest, ActionResponse]],
            channel
          )
        } match {
          case Success(_) =>
          case Failure(ex) =>
            listener.onFailure(new SecurityPermissionException("Cannot handle proxy request", ex))
        }
      case (Some(_), None) =>
        chain.proceed(task, action, request, listener)
      case (None, Some(_)) =>
        listener.onFailure(createRorNotReadyYetResponse())
      case (None, None) =>
        throw new IllegalStateException("Cannot process current request")
    }
  }

  def stop(): MTask[Unit] = rorInstance.stop()

  private def handleRequest(engine: Engine,
                            task: Task,
                            action: String,
                            request: ActionRequest,
                            listener: ActionListener[ActionResponse],
                            chain: ActionFilterChain[ActionRequest, ActionResponse],
                            channel: ProxyRestChannel): Unit = {
    aclAwareRequestFilter
      .handle(
        engine,
        EsContext(channel, task, action, request, listener, chain)
      )
      .runAsync {
        case Right(_) =>
        case Left(ex) => channel.sendFailureResponse(ex)
      }
  }
}

object ProxyIndexLevelActionFilter {

  def create(configFile: Path,
             esClient: RestHighLevelClientAdapter,
             threadPool: ThreadPool)
            (implicit scheduler: Scheduler,
             generator: UniqueIdentifierGenerator,
             envVarsProvider: EnvVarsProvider): MTask[Either[StartingFailure, ProxyIndexLevelActionFilter]] = {
    val result = for {
      instance <- EitherT(
        new Ror(
          mode = RorMode.Proxy,
          envVarsProvider = envVarsProvider
        )
          .start(configFile, new ProxyAuditSinkService(esClient), ProxyIndexJsonContentService)
      )
      _ = RorInstanceSupplier.update(instance)
    } yield new ProxyIndexLevelActionFilter(instance, esClient, threadPool)
    result.value
  }

  private class ThreadRepoChannelRenewalOnChainProceed[Request <: ActionRequest, Response <: ActionResponse](underlying: ActionFilterChain[Request, Response],
                                                                                                             channel: ProxyRestChannel)
    extends ActionFilterChain[Request, Response] {

    override def proceed(task: Task, action: String, request: Request, listener: ActionListener[Response]): Unit = {
      ProxyThreadRepo.setRestChannel(channel)
      underlying.proceed(task, action, request, listener)
    }
  }
}
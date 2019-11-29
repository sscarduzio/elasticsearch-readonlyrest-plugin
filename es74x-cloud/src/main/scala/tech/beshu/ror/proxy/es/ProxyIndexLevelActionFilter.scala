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
import org.elasticsearch.rest.RestChannel
import org.elasticsearch.tasks.Task
import org.elasticsearch.threadpool.ThreadPool
import tech.beshu.ror.SecurityPermissionException
import tech.beshu.ror.accesscontrol.domain.UriPath.CurrentUserMetadataPath
import tech.beshu.ror.accesscontrol.request.EsRequestContext
import tech.beshu.ror.boot.{Engine, Ror, RorInstance, StartingFailure}
import tech.beshu.ror.es.request.RequestInfo
import tech.beshu.ror.es.request.RorNotAvailableResponse.createRorNotReadyYetResponse
import tech.beshu.ror.es.request.regular.RegularRequestHandler
import tech.beshu.ror.es.request.usermetadata.CurrentUserMetadataRequestHandler
import tech.beshu.ror.proxy.es.ProxyIndexLevelActionFilter.ThreadRepoChannelRenewalOnChainProceed
import tech.beshu.ror.proxy.es.clients.RestHighLevelClientAdapter
import tech.beshu.ror.proxy.es.providers.{EsRestClientBasedRorClusterService, ProxyAuditSink, ProxyIndexJsonContentManager}

import scala.util.{Failure, Success, Try}

class ProxyIndexLevelActionFilter private(rorInstance: RorInstance,
                                          esClient: RestHighLevelClientAdapter,
                                          threadPool: ThreadPool)
                                         (implicit scheduler: Scheduler)
  extends ActionFilter {

  private val rorClusterService = new EsRestClientBasedRorClusterService(esClient)

  override def order(): Int = 0

  override def apply[Request <: ActionRequest, Response <: ActionResponse](task: Task,
                                                                           action: String,
                                                                           request: Request,
                                                                           listener: ActionListener[Response],
                                                                           chain: ActionFilterChain[Request, Response]): Unit = {
    (rorInstance.engine, ProxyThreadRepo.getRestChannel) match {
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
      case (None, Some(channel)) =>
        channel.sendResponse(createRorNotReadyYetResponse(channel))
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
                            channel: RestChannel): Unit = {
    val requestInfo = new RequestInfo(channel, task.getId, action, request, rorClusterService, threadPool, false)
    val requestContext = EsRequestContext.from(requestInfo).get
    requestContext.uriPath match {
      case CurrentUserMetadataPath(_) =>
        val handler = new CurrentUserMetadataRequestHandler(engine, task, action, request, listener, chain, channel, threadPool)
        handler.handle(requestInfo, requestContext)
      case _ =>
        val handler = new RegularRequestHandler(engine, task, action, request, listener, chain, channel, threadPool)
        handler.handle(requestInfo, requestContext)
    }
  }

}

object ProxyIndexLevelActionFilter {

  def create(configFile: Path,
             esClient: RestHighLevelClientAdapter,
             threadPool: ThreadPool)
            (implicit scheduler: Scheduler): MTask[Either[StartingFailure, ProxyIndexLevelActionFilter]] = {
    val result = for {
      instance <- EitherT(Ror.start(configFile, ProxyAuditSink, ProxyIndexJsonContentManager))
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
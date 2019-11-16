package tech.beshu.ror.es.proxy

import java.nio.file.Path

import cats.data.EitherT
import monix.eval.{Task => MTask}
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
import monix.execution.Scheduler.Implicits.global
import org.apache.http.HttpHost
import org.elasticsearch.client.{RestClient, RestHighLevelClient}

import scala.util.{Failure, Success, Try}

class ProxyIndexLevelActionFilter private(rorInstance: RorInstance,
                                          threadPool: ThreadPool)
  extends ActionFilter {

  private val esClient = new RestHighLevelClient(RestClient.builder(HttpHost.create("http://localhost:9200"))) // todo: configuration
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
            chain.asInstanceOf[ActionFilterChain[ActionRequest, ActionResponse]],
            channel
          )
        } match {
          case Success(_) =>
          case Failure(ex) =>
            listener.onFailure(new SecurityPermissionException("Cannot handle proxy request", ex))
        }
      case (None, Some(channel)) =>
        channel.sendResponse(createRorNotReadyYetResponse(channel))
      case (_, None) =>
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
             threadPool: ThreadPool): MTask[Either[StartingFailure, ProxyIndexLevelActionFilter]] = {
    val result = for {
      instance <- EitherT(Ror.start(configFile, ProxyAuditSink, ProxyIndexJsonContentManager))
    } yield new ProxyIndexLevelActionFilter(instance, threadPool)
    result.value
  }
}
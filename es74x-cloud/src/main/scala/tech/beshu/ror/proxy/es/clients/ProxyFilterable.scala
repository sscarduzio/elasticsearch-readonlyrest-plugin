package tech.beshu.ror.proxy.es.clients

import java.util.concurrent.atomic.AtomicLong

import monix.eval.Task
import monix.execution.Scheduler
import org.elasticsearch.ElasticsearchStatusException
import org.elasticsearch.action.{ActionListener, ActionRequest, ActionResponse}
import org.elasticsearch.tasks.{Task => EsTask}
import tech.beshu.ror.proxy.es.exceptions.RorProxyException
import tech.beshu.ror.proxy.es.{ProxyIndexLevelActionFilter, ProxyThreadRepo}

import scala.collection.JavaConverters._

trait ProxyFilterable {

  private val taskIdGenerator = new AtomicLong(0)

  implicit def scheduler: Scheduler

  def proxyFilter: ProxyIndexLevelActionFilter

  protected def passThrough(): Unit = {
    ProxyThreadRepo.getRestChannel match {
      case Some(channel) =>
        channel.passThrough()
      case None =>
        throw new RorProxyException("Cannot find rest channel for given request")
    }
  }

  protected def execute[REQ <: ActionRequest, RESP <: ActionResponse](action: String,
                                                                      request: REQ,
                                                                      listener: ActionListener[RESP])
                                                                     (handler: REQ => Task[RESP]): Unit = {
    val task = createNewTask(request, action)
    proxyFilter.apply(
      task,
      action,
      request,
      listener,
      (_: EsTask, _: String, request: REQ, listener: ActionListener[RESP]) => {
        handler(request).runAsync(handleResultUsing(listener))
      }
    )
  }

  private def handleResultUsing[T](listener: ActionListener[T])
                                  (result: Either[Throwable, T]): Unit = result match {
    case Right(response) => listener.onResponse(response)
    case Left(RorProxyException(_, ex: ElasticsearchStatusException)) => listener.onFailure(ex)
    case Left(ex: Exception) => listener.onFailure(ex)
    case Left(ex: Throwable) => listener.onFailure(new RuntimeException(ex))
  }

  private def createNewTask(request: ActionRequest, action: String) = {
    request.createTask(taskIdGenerator.incrementAndGet(), null, action, null, Map.empty[String, String].asJava)
  }
}

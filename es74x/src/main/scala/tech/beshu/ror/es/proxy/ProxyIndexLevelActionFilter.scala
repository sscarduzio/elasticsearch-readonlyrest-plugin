package tech.beshu.ror.es.proxy

import java.nio.file.Path

import cats.data.EitherT
import monix.eval.{Task => MTask}
import org.elasticsearch.action.support.{ActionFilter, ActionFilterChain}
import org.elasticsearch.action.{ActionListener, ActionRequest, ActionResponse}
import org.elasticsearch.tasks.Task
import tech.beshu.ror.boot.{Ror, RorInstance, StartingFailure}
import tech.beshu.ror.es.request.RorNotAvailableResponse.createRorNotReadyYetResponse

class ProxyIndexLevelActionFilter private(rorInstance: RorInstance) extends ActionFilter {
  override def order(): Int = 0

  override def apply[Request <: ActionRequest, Response <: ActionResponse](task: Task,
                                                                           action: String,
                                                                           request: Request,
                                                                           listener: ActionListener[Response],
                                                                           chain: ActionFilterChain[Request, Response]): Unit = {
    (rorInstance.engine, ProxyThreadRepo.getRestChannel) match {
      case (None, Some(channel)) =>
        channel.sendResponse(createRorNotReadyYetResponse(channel))
      case _ =>
        chain.proceed(task, action, request, listener)
    }
  }

  def stop(): MTask[Unit] = rorInstance.stop()
}

object ProxyIndexLevelActionFilter {

  def create(configFile: Path): MTask[Either[StartingFailure, ProxyIndexLevelActionFilter]] = {
    val result = for {
      instance <- EitherT(Ror.start(configFile, ProxyAuditSink, ProxyIndexJsonContentManager))
    } yield new ProxyIndexLevelActionFilter(instance)
    result.value
  }
}
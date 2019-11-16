package tech.beshu.ror.es.proxy

import cats.data.EitherT
import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.implicits._
import com.twitter.finagle.{Http, ListeningServer}
import com.twitter.util.Future
import io.finch.ToAsync
import monix.execution.Scheduler.Implicits.global
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.boot.StartingFailure
import tech.beshu.ror.utils.ScalaOps.taskToIo

object Boot extends IOApp with Logging {

  override def run(args: List[String]): IO[ExitCode] = {
    runServer.flatMap {
      case Right(listeningServer) =>
        val server = Resource.make(IO(listeningServer))(s =>
          IO.suspend(implicitly[ToAsync[Future, IO]].apply(s.close()))
        )
        server.use(_ => IO.never).as(ExitCode.Success)
      case Left(startingFailure) =>
        val errorMessage = s"Cannot start ReadonlyREST proxy: ${startingFailure.message}"
        startingFailure.throwable match {
          case Some(ex) => logger.error(errorMessage, ex)
          case None => logger.error(errorMessage)
        }
        IO.pure(ExitCode.Error)
    }
  }


  private def runServer: IO[Either[StartingFailure, ListeningServer]] = {
    val result = for {
      simulator <- EitherT(EsRestServiceSimulator.create())
      server = Http.server.serve(":5000", new ProxyRestInterceptorService(simulator))
    } yield server
    result.value
  }

}

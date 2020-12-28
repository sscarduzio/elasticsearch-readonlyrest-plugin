package tech.beshu.ror.proxy.utils

import cats.effect.{ContextShift, IO}
import monix.eval.Task
import monix.execution.Scheduler
import com.twitter.{util => twitter}

import scala.concurrent.{Future, Promise}
import scala.language.implicitConversions
import scala.util.{Failure, Success, Try}

object TwitterFutureOps {

  implicit def twitterToScalaTry[T](t: twitter.Try[T]): Try[T] = t match {
    case twitter.Return(r) => Success(r)
    case twitter.Throw(ex) => Failure(ex)
  }

  implicit def twitterToScalaFuture[T](f: twitter.Future[T]): Future[T] = {
    val promise = Promise[T]()
    f.respond(promise complete _)
    promise.future
  }

  implicit def taskToTwitterFuture[T](t: Task[T])
                                     (implicit scheduler: Scheduler): twitter.Future[T] = {
    val promise = twitter.Promise[T]()
    t.runAsync {
      case Right(value) => promise.setValue(value)
      case Left(ex) => promise.setException(ex)
    }
    promise
  }

  implicit def twitterFutureToTask[T](f: twitter.Future[T]): Task[T] = {
    Task.fromFuture(f)
  }

  implicit def twitterFutureToIo[T](f: twitter.Future[T])
                                   (implicit contextShift: ContextShift[IO]): IO[T] = {
    IO.fromFuture(IO(f))
  }

}

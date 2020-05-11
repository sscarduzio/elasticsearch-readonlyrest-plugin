package tech.beshu.ror.utils.misc

import java.time.Duration

import cats.implicits._
import cats.Functor
import monix.eval.Task

import scala.concurrent.duration.FiniteDuration
import scala.language.{higherKinds, implicitConversions}

object ScalaUtils {

  implicit class ListOps[T](val list: List[T]) extends AnyVal {
    def partitionByIndexMod2: (List[T], List[T]) = {
      list.zipWithIndex.partition(_._2 % 2 == 0).bimap(_.map(_._1), _.map(_._1))
    }
  }

  implicit class AutoCloseableOps[A <: AutoCloseable](val value: A) {
    def bracket[B](convert: A => B): B = {
      try {
        convert(value)
      } finally {
        value.close()
      }
    }
  }

  implicit class AutoClosableMOps[A <: AutoCloseable, M[_]: Functor](val value: M[A]) {
    def bracket[B](convert: A => B): M[B] = {
      value.map(v => AutoCloseableOps(v).bracket(convert))
    }
  }

  implicit def finiteDurationToJavaDuration(interval: FiniteDuration): Duration = Duration.ofMillis(interval.toMillis)

  def retry(times: Int)(action: Unit): Unit = {
    Stream.fill(times)(()).foreach(_ => action)
  }

  def retryBackoff[A](source: Task[A],
                      maxRetries: Int,
                      firstDelay: FiniteDuration,
                      backOffScaler: Int): Task[A] = {

    source.onErrorHandleWith {
      case ex: Exception =>
        if (maxRetries > 0)
          retryBackoff(source, maxRetries - 1, firstDelay * backOffScaler, backOffScaler)
            .delayExecution(firstDelay)
        else
          Task.raiseError(ex)
    }
  }

}

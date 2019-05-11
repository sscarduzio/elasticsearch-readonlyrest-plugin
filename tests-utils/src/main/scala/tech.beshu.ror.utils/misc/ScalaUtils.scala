package tech.beshu.ror.utils.misc

import java.time.Duration

import cats.Functor
import cats.implicits._

import scala.concurrent.duration.FiniteDuration
import scala.language.{higherKinds, implicitConversions}

object ScalaUtils {

  implicit class ListOps[T](val list: List[T]) extends AnyVal {
    def partitionByIndexMod2: (List[T], List[T]) = {
      list.zipWithIndex.partition(_._2 % 2 == 0).bimap(_.map(_._1), _.map(_._1))
    }
  }

  implicit class AutoCloseableOps[A <: AutoCloseable](val value: A) extends AnyVal {
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

}

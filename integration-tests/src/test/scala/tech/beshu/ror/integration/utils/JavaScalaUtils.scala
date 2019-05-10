package tech.beshu.ror.integration.utils

import java.time.Duration

import cats.Functor
import cats.implicits._

import scala.concurrent.duration.FiniteDuration
import scala.language.{higherKinds, implicitConversions}

object JavaScalaUtils {

  def bracket[A <: AutoCloseable,B](closeableAction: A)(convert: A => B): B = {
    try {
      convert(closeableAction)
    } finally {
      closeableAction.close()
    }
  }

  def bracket[A <: AutoCloseable, M[_]: Functor ,B](closeableAction: M[A])(convert: A => B): M[B] = {
    closeableAction.map(bracket(_)(convert))
  }

  implicit def finiteDurationToJavaDuration(interval: FiniteDuration): Duration = Duration.ofMillis(interval.toMillis)
}

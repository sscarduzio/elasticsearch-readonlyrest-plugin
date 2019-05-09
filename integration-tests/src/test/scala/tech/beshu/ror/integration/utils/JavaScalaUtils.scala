package tech.beshu.ror.integration.utils

import cats.Functor
import cats.implicits._

import scala.language.higherKinds

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
}

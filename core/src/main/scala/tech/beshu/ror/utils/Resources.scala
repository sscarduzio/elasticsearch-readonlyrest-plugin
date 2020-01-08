package tech.beshu.ror.utils

import cats.effect.{IO, Resource}

import scala.util.Try

object Resources {

  def from[A <: AutoCloseable, B](closeable: => A)(use: A => B): Try[B] = Try {
    Resource.fromAutoCloseable(IO(closeable))
      .use(resource => IO(use(resource)))
      .unsafeRunSync()
  }
}

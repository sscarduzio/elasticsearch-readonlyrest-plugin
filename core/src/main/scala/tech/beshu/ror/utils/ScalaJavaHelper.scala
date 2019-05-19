package tech.beshu.ror.utils

import scala.util.{Failure, Success, Try}

object ScalaJavaHelper {

  def force[T](value: Try[T]): T = value match {
    case Success(v) => v
    case Failure(exception) => throw exception
  }
}

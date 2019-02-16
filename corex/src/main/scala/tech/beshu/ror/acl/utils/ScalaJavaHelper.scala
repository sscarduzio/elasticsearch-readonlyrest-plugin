package tech.beshu.ror.acl.utils

import scala.util.{Failure, Success, Try}

object ScalaJavaHelper {

  def force[T](value: Try[T]): T = value match {
    case Success(value) => value
    case Failure(exception) => throw exception
  }
}

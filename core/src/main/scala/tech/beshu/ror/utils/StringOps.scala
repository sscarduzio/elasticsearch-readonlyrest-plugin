package tech.beshu.ror.utils

import eu.timepit.refined.types.string.NonEmptyString

import scala.language.implicitConversions

class StringOps(val value: String) extends AnyVal {

  def toNonEmptyStringsTuple: Option[(NonEmptyString, NonEmptyString)] = {
    value.split(':').toList match {
      case Nil => None
      case x :: xs =>
        for {
          first <- NonEmptyString.unapply(x)
          second <- NonEmptyString.unapply(xs.foldLeft("")(_ + _))
        } yield (first, second)
    }
  }
}

object StringOps {
  implicit def toStringOps(value: String): StringOps = new StringOps(value)
}

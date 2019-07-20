package tech.beshu.ror.utils

import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.utils.StringWiseSplitter.Error.{CannotSplitUsingColon, TupleMemberCannotBeEmpty}

import scala.language.implicitConversions

class StringWiseSplitter(val value: String) extends AnyVal {

  def toNonEmptyStringsTuple: Either[StringWiseSplitter.Error, (NonEmptyString, NonEmptyString)] = {
    val colonIndex = value.indexOf(':')
    colonIndex match {
      case -1 => Left(CannotSplitUsingColon)
      case idx =>
        val (beforeColon, secondPartOfString) = value.splitAt(idx)
        val result = for {
          first <- NonEmptyString.from(beforeColon)
          second <- NonEmptyString.from(secondPartOfString.substring(1))
        } yield (first, second)
        result.left.map(_ => TupleMemberCannotBeEmpty)
    }
  }
}

object StringWiseSplitter {
  implicit def toStringOps(value: String): StringWiseSplitter = new StringWiseSplitter(value)

  sealed trait Error
  object Error {
    case object CannotSplitUsingColon extends Error
    case object TupleMemberCannotBeEmpty extends Error
  }
}

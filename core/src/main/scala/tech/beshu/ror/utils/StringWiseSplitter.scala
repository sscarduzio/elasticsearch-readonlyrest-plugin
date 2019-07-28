/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
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

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

import cats.Functor
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.NonNegative
import eu.timepit.refined.types.all.NonEmptyString
import squants.information.Information
import tech.beshu.ror.utils.DurationOps.{NonNegativeFiniteDuration, PositiveFiniteDuration, RefinedDurationOps}

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.{Failure, Success, Try}

trait FromString[T] {
  def decode(str: String): Either[String, T]
  def map[B](f: T => B): FromString[B] = str => decode(str).map(f)
}

object FromString {

  def instance[T](f: String => Either[String, T]): FromString[T] = str => f(str)

  implicit val functor: Functor[FromString] = new Functor[FromString] {
    override def map[A, B](fa: FromString[A])(f: A => B): FromString[B] =
      str => fa.decode(str).map(f)
  }

  val string: FromString[String] = instance(Right(_))

  val boolean: FromString[Boolean] = instance { str =>
    str.toLowerCase match {
      case "true"  => Right(true)
      case "false" => Right(false)
      case other   => Left(s"Cannot convert '$other' to boolean")
    }
  }

  val nonNegativeFiniteDuration: FromString[NonNegativeFiniteDuration] = instance { str =>
    Try(Duration(str)) match {
      case Success(v: FiniteDuration) =>
        v.toRefineNonNegative.left.map(_ => s"Duration '$str' must be non-negative")
      case Success(_) | Failure(_) =>
        Left(s"Cannot parse '$str' as a duration. Expected a finite duration like '5s', '1m'")
    }
  }

  val positiveFiniteDuration: FromString[PositiveFiniteDuration] = instance { str =>
    Try(Duration(str)) match {
      case Success(v: FiniteDuration) =>
        v.toRefinedPositive.left.map(_ => s"Duration '$str' must be positive (greater than zero)")
      case Success(_) | Failure(_) =>
        Left(s"Cannot parse '$str' as a duration. Expected a finite duration like '5s', '1m'")
    }
  }

  val nonNegativeInt: FromString[Int Refined NonNegative] = instance { str =>
    Try(Integer.valueOf(str)) match {
      case Success(int) if int >= 0 => Right(Refined.unsafeApply(int))
      case Success(_) | Failure(_)  => Left(s"Cannot convert '$str' to non-negative integer")
    }
  }

  val nonEmptyString: FromString[NonEmptyString] = instance { str =>
    NonEmptyString.from(str).left.map(_ => "Must not be empty")
  }

  val information: FromString[Information] = instance { str =>
    Information.parseString(str).toEither
      .left.map(_ => s"Cannot parse '$str' as a data size. Expected format like '1 MB', '512 KB'")
  }
}

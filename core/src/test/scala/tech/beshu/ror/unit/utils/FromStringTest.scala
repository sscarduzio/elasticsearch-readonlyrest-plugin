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
package tech.beshu.ror.unit.utils

import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import squants.information.Megabytes
import tech.beshu.ror.utils.DurationOps.RefinedDurationOps
import tech.beshu.ror.utils.FromString

import scala.concurrent.duration.*
import scala.language.postfixOps

class FromStringTest extends AnyWordSpec {

  "FromString.string" should {
    "return any string as-is" in {
      FromString.string.decode("hello") should be(Right("hello"))
    }
    "return an empty string as-is" in {
      FromString.string.decode("") should be(Right(""))
    }
  }

  "FromString.boolean" should {
    "decode 'true'" in {
      FromString.boolean.decode("true") should be(Right(true))
    }
    "decode 'false'" in {
      FromString.boolean.decode("false") should be(Right(false))
    }
    "be case-insensitive" in {
      FromString.boolean.decode("True") should be(Right(true))
      FromString.boolean.decode("FALSE") should be(Right(false))
    }
    "fail for unrecognised values" in {
      FromString.boolean.decode("yes") should be(Left("Cannot convert 'yes' to boolean"))
    }
  }

  "FromString.nonNegativeFiniteDuration" should {
    "decode a duration with unit" in {
      FromString.nonNegativeFiniteDuration.decode("5s") should be(Right((5 seconds).toRefinedNonNegativeUnsafe))
    }
    "decode zero duration" in {
      FromString.nonNegativeFiniteDuration.decode("0s") should be(Right((0 seconds).toRefinedNonNegativeUnsafe))
    }
    "decode minutes" in {
      FromString.nonNegativeFiniteDuration.decode("2 minutes") should be(Right((2 minutes).toRefinedNonNegativeUnsafe))
    }
    "fail for a negative duration" in {
      FromString.nonNegativeFiniteDuration.decode("-1s") should be(Left("Duration '-1s' must be non-negative"))
    }
    "fail for an infinite duration" in {
      FromString.nonNegativeFiniteDuration.decode("Inf") should be(Left(
        "Cannot parse 'Inf' as a duration. Expected a finite duration like '5s', '1m'"
      ))
    }
    "decode a bare integer as seconds" in {
      FromString.nonNegativeFiniteDuration.decode("5") should be(Right((5 seconds).toRefinedNonNegativeUnsafe))
    }
    "decode bare zero as seconds" in {
      FromString.nonNegativeFiniteDuration.decode("0") should be(Right((0 seconds).toRefinedNonNegativeUnsafe))
    }
    "fail for a non-duration string" in {
      FromString.nonNegativeFiniteDuration.decode("not-a-duration") should be(Left(
        "Cannot parse 'not-a-duration' as a duration. Expected a finite duration like '5s', '1m'"
      ))
    }
  }

  "FromString.positiveFiniteDuration" should {
    "decode a positive duration" in {
      FromString.positiveFiniteDuration.decode("10s") should be(Right((10 seconds).toRefinedPositiveUnsafe))
    }
    "decode a bare integer as seconds" in {
      FromString.positiveFiniteDuration.decode("10") should be(Right((10 seconds).toRefinedPositiveUnsafe))
    }
    "fail for zero duration" in {
      FromString.positiveFiniteDuration.decode("0s") should be(Left("Duration '0s' must be positive (greater than zero)"))
    }
    "fail for bare zero integer" in {
      FromString.positiveFiniteDuration.decode("0") should be(Left("Duration '0' must be positive (greater than zero)"))
    }
    "fail for a negative duration" in {
      FromString.positiveFiniteDuration.decode("-5s") should be(Left("Duration '-5s' must be positive (greater than zero)"))
    }
    "fail for an infinite duration" in {
      FromString.positiveFiniteDuration.decode("Inf") should be(Left(
        "Cannot parse 'Inf' as a duration. Expected a finite duration like '5s', '1m'"
      ))
    }
  }

  "FromString.nonNegativeInt" should {
    "decode zero" in {
      FromString.nonNegativeInt.decode("0").map(_.value) should be(Right(0))
    }
    "decode a positive integer" in {
      FromString.nonNegativeInt.decode("42").map(_.value) should be(Right(42))
    }
    "fail for a negative integer" in {
      FromString.nonNegativeInt.decode("-1") should be(Left("Cannot convert '-1' to non-negative integer"))
    }
    "fail for a non-integer string" in {
      FromString.nonNegativeInt.decode("abc") should be(Left("Cannot convert 'abc' to non-negative integer"))
    }
    "fail for a floating-point string" in {
      FromString.nonNegativeInt.decode("3.14") should be(Left("Cannot convert '3.14' to non-negative integer"))
    }
  }

  "FromString.nonEmptyString" should {
    "decode a non-empty string" in {
      FromString.nonEmptyString.decode("hello").map(_.value) should be(Right("hello"))
    }
    "fail for an empty string" in {
      FromString.nonEmptyString.decode("") should be(Left("Must not be empty"))
    }
  }

  "FromString.information" should {
    "decode megabytes" in {
      FromString.information.decode("3 MB") should be(Right(Megabytes(3)))
    }
    "fail for an unrecognised format" in {
      FromString.information.decode("lots") should be(Left(
        "Cannot parse 'lots' as a data size. Expected format like '1 MB', '512 KB'"
      ))
    }
  }

  "FromString.map" should {
    "transform a successful decode result" in {
      FromString.string.map(_.length).decode("hello") should be(Right(5))
    }
    "propagate a decoding failure without applying the function" in {
      FromString.nonNegativeInt.map(_.value * 2).decode("bad") should be(Left(
        "Cannot convert 'bad' to non-negative integer"
      ))
    }
  }
}

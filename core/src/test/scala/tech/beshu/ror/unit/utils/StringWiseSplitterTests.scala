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

import eu.timepit.refined.types.string.NonEmptyString
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.utils.StringWiseSplitter
import tech.beshu.ror.utils.StringWiseSplitter.*
import tech.beshu.ror.utils.TestsUtils.unsafeNes

class StringWiseSplitterTests extends AnyWordSpec {

  "A StringOps method toNonEmptyStringsTuple" should {
    "be able to create two non-empty string tuple from string" when {
      "string contains one colon somewhere in the middle" in {
        "example:test".toNonEmptyStringsTuple should be (Right(NonEmptyString.unsafeFrom("example"), NonEmptyString.unsafeFrom("test")))
      }
      "string contains two colons" in {
        "example:test:test".toNonEmptyStringsTuple should be (Right(NonEmptyString.unsafeFrom("example"), NonEmptyString.unsafeFrom("test:test")))
      }
      "there are two colons at the end of string" in {
        "example::".toNonEmptyStringsTuple should be (Right(NonEmptyString.unsafeFrom("example"), NonEmptyString.unsafeFrom(":")))
      }
    }
    "not be able to create two non-empty string tuple from string" when {
      "there is no colon in string" in {
        "test".toNonEmptyStringsTuple should be (Left(StringWiseSplitter.Error.CannotSplitUsingColon))
      }
      "colon is at the beginning of string" in {
        ":test".toNonEmptyStringsTuple should be (Left(StringWiseSplitter.Error.TupleMemberCannotBeEmpty))
      }
      "there is one colon at the end of string" in {
        "test:".toNonEmptyStringsTuple should be (Left(StringWiseSplitter.Error.TupleMemberCannotBeEmpty))
      }
    }
  }
}

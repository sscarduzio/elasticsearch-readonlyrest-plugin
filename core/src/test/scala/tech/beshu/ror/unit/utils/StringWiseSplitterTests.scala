package tech.beshu.ror.unit.utils

import org.scalatest.WordSpec
import tech.beshu.ror.utils.StringWiseSplitter._
import org.scalatest.Matchers._
import tech.beshu.ror.utils.StringWiseSplitter
import tech.beshu.ror.utils.TestsUtils._

class StringWiseSplitterTests extends WordSpec {

  "A StringOps method toNonEmptyStringsTuple" should {
    "be able to create two non-empty string tuple from string" when {
      "string contains one colon somewhere in the middle" in {
        "example:test".toNonEmptyStringsTuple should be (Right("example".nonempty, "test".nonempty))
      }
      "string contains two colons" in {
        "example:test:test".toNonEmptyStringsTuple should be (Right("example".nonempty, "test:test".nonempty))
      }
      "there are two colons at the end of string" in {
        "example::".toNonEmptyStringsTuple should be (Right("example".nonempty, ":".nonempty))
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

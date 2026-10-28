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

import org.scalactic.anyvals.PosInt
import org.scalatest.concurrent.TimeLimits
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.prop.Tables.Table
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import tech.beshu.ror.accesscontrol.matchers.WildcardPattern
import tech.beshu.ror.utils.WildcardPatternGenerators.*

class WildcardPatternTests
    extends AnyWordSpec
    with ScalaCheckDrivenPropertyChecks
    with TableDrivenPropertyChecks
    with TimeLimits {

  import WildcardPatternTests.*

  override implicit val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(
      minSuccessful = 10000,
      workers = PosInt.ensuringValid(Runtime.getRuntime.availableProcessors())
    )

  "WildcardPattern" should {
    "match the examples" when {
      "`?` is a literal" in {
        forAll(commonExamples ++ literalQuestionMarkExamples) { (pattern, value, expected) =>
          new WildcardPattern(pattern, singleCharWildcard = false).matches(value) should be(expected)
        }
      }
      "`?` is a one-character wildcard" in {
        forAll(commonExamples ++ singleCharWildcardExamples) { (pattern, value, expected) =>
          new WildcardPattern(pattern, singleCharWildcard = true).matches(value) should be(expected)
        }
      }
    }
    "match a pattern without wildcards only to itself" in {
      forAll(genLiteral, genLiteral) { (pattern, value) =>
        bothModes(pattern).foreach(_.matches(pattern) should be(true))
        bothModes(pattern).foreach(_.matches(value) should be(pattern == value))
      }
    }
    "match `*` to any value" in {
      forAll(genAnyString) { value =>
        bothModes("*").foreach(_.matches(value) should be(true))
      }
    }
    "match `literal*` to the values that start with the literal" in {
      forAll(genLiteral, genAnyString) { (literal, value) =>
        bothModes(s"$literal*").foreach(_.matches(value) should be(value.startsWith(literal)))
        bothModes(s"$literal*").foreach(_.matches(literal + value) should be(true))
      }
    }
    "match `*literal` to the values that end with the literal" in {
      forAll(genLiteral, genAnyString) { (literal, value) =>
        bothModes(s"*$literal").foreach(_.matches(value) should be(value.endsWith(literal)))
        bothModes(s"*$literal").foreach(_.matches(value + literal) should be(true))
      }
    }
    "match `*literal*` to the values that contain the literal" in {
      forAll(genLiteral, genAnyString) { (literal, value) =>
        bothModes(s"*$literal*").foreach(_.matches(value) should be(value.contains(literal)))
      }
    }
    "match a pattern to each value made from it" when {
      "each `*` becomes any text" in {
        forAll(genPatternAndInstance(singleCharWildcard = false)) { case (pattern, value) =>
          new WildcardPattern(pattern, singleCharWildcard = false).matches(value) should be(true)
        }
      }
      "each `*` becomes any text and each `?` one character" in {
        forAll(genPatternAndInstance(singleCharWildcard = true)) { case (pattern, value) =>
          new WildcardPattern(pattern, singleCharWildcard = true).matches(value) should be(true)
        }
      }
    }
    "not match a value shorter than the pattern without its `*`" in {
      forAll(genPatternAndInstance(singleCharWildcard = true)) { case (pattern, value) =>
        val minimalLength = pattern.count(_ != '*')
        whenever(minimalLength > 0) {
          new WildcardPattern(pattern, singleCharWildcard = true).matches(value.take(minimalLength - 1)) should be(
            false
          )
        }
      }
    }
    "not backtrack" when {
      // A backtracking matcher does not end on these values, so the limit only separates linear time from backtracking.
      "the value almost matches a pattern with many wildcards" in {
        val value = "a" * 10000
        failAfter(Span(10, Seconds)) {
          new WildcardPattern("*a*a*a*a*a*b", singleCharWildcard = false).matches(value) should be(false)
          new WildcardPattern("*a*a*a*a*a*c*b", singleCharWildcard = false).matches(value + "b") should be(false)
          new WildcardPattern("*a?a*a?a*a?b", singleCharWildcard = true).matches(value) should be(false)
          new WildcardPattern(s"*${"a" * 1000}b*", singleCharWildcard = false).matches(value) should be(false)
        }
      }
    }
  }

}

object WildcardPatternTests {

  private val commonExamples = Table(
    ("pattern", "value", "expected"),
    ("", "", true),
    ("", "a", false),
    ("abc", "abc", true),
    ("abc", "ab", false),
    ("abc", "abcd", false),
    ("abc", "ABC", false),
    ("*", "", true),
    ("*", "a\nb", true),
    ("**", "", true),
    ("a*", "a", true),
    ("a*", "ab", true),
    ("a*", "ba", false),
    ("*b", "ab", true),
    ("*b", "ba", false),
    ("a*b", "a b", true),
    ("a*b", "ab", true),
    ("a*b", "abc", false),
    ("*a*", "bab", true),
    ("*a*", "bbb", false),
    ("a**b", "axb", true),
    // the prefix and the suffix must not overlap
    ("ab*ba", "aba", false),
    ("ab*ba", "abba", true),
    // each piece must come after the one before
    ("*b*a", "ab", false),
    ("*aa*aa*", "aaa", false),
    ("*aa*aa*", "aaaa", true),
    ("*a*b*c", "cba", false),
    ("*a*b*c", "xaybzc", true),
    // regex metacharacters, line terminators and non-ASCII characters match only themselves
    ("a(b", "a(b", true),
    ("a(b", "ab", false),
    ("price(usd)", "price(usd)", true),
    ("price(usd)", "priceusd", false),
    ("a.c", "abc", false),
    ("a|b", "a", false),
    ("a+", "aa", false),
    ("[ab]", "a", false),
    ("[ab]", "[ab]", true),
    ("{1}", "{1}", true),
    ("^a$", "^a$", true),
    ("\\d", "1", false),
    ("\\d", "\\d", true),
    ("a\nb", "a\nb", true),
    ("a\nb", "a\rb", false),
    ("\u0085*", "\u0085x", true),
    ("é*", "é", true),
    ("é*", "e", false),
    ("*😀", "x😀", true),
    ("😀", "😀", true)
  )

  private val literalQuestionMarkExamples = Table(
    ("pattern", "value", "expected"),
    ("a?c", "a?c", true),
    ("a?c", "abc", false),
    ("?", "a", false),
    ("*?", "a?", true),
    ("*?", "ab", false)
  )

  private val singleCharWildcardExamples = Table(
    ("pattern", "value", "expected"),
    ("a?c", "abc", true),
    ("a?c", "a?c", true),
    ("a?c", "ac", false),
    ("a?c", "abbc", false),
    ("?", "", false),
    ("?", "a", true),
    ("?", "\n", true),
    ("*?", "", false),
    ("*?", "ab", true),
    ("a?*?b", "a12b", true),
    ("a?*?b", "a1b", false),
    ("*a?c*", "xxabcxx", true),
    ("*a?c*", "xxacxx", false),
    // `?` matches one UTF-16 character, and the emoji is two
    ("?", "😀", false),
    ("??", "😀", true)
  )

  private def bothModes(pattern: String) =
    List(
      new WildcardPattern(pattern, singleCharWildcard = false),
      new WildcardPattern(pattern, singleCharWildcard = true)
    )

}

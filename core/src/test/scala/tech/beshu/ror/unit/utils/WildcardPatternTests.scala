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

import org.scalacheck.Gen
import org.scalactic.anyvals.PosInt
import org.scalatest.concurrent.TimeLimits
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.time.{Seconds, Span}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import tech.beshu.ror.accesscontrol.matchers.WildcardPattern

class WildcardPatternTests extends AnyWordSpec with ScalaCheckDrivenPropertyChecks with TimeLimits {

  import WildcardPatternTests.*

  override implicit val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(
      minSuccessful = 50000,
      workers = PosInt.ensuringValid(Runtime.getRuntime.availableProcessors())
    )

  "WildcardPattern" should {
    "match like a reference glob" when {
      "`?` is a literal" in {
        forAll(genPatternAndValue(singleCharWildcard = false)) { case (pattern, value) =>
          new WildcardPattern(pattern, singleCharWildcard = false).matches(value) should be(
            referenceMatches(pattern, singleCharWildcard = false, value)
          )
        }
      }
      "`?` is a one-character wildcard" in {
        forAll(genPatternAndValue(singleCharWildcard = true)) { case (pattern, value) =>
          new WildcardPattern(pattern, singleCharWildcard = true).matches(value) should be(
            referenceMatches(pattern, singleCharWildcard = true, value)
          )
        }
      }
    }
    "match `?` as one character only when the flag is on" in {
      new WildcardPattern("a?c", singleCharWildcard = true).matches("abc") should be(true)
      new WildcardPattern("a?c", singleCharWildcard = false).matches("abc") should be(false)
      new WildcardPattern("a?c", singleCharWildcard = false).matches("a?c") should be(true)
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

  // Regex metacharacters, `*`, `?`, `.`, line terminators and non-ASCII characters. The emoji is a surrogate pair.
  private val alphabet = ("ab*?.^$|+()[]{}\\- é日\n\r \u0085".map(_.toString) :+ "😀").toVector

  // Few symbols make repeated and overlapping pieces frequent.
  private val smallAlphabet = "ab*?.".map(_.toString).toVector

  def genPatternAndValue(singleCharWildcard: Boolean): Gen[(String, String)] =
    for {
      symbols <- Gen.oneOf(alphabet, smallAlphabet)
      pattern <- Gen.frequency(19 -> genString(symbols, 10), 1 -> genString(symbols, 60))
      value <- genValue(symbols, pattern, singleCharWildcard)
    } yield (pattern, value)

  // Half of the values come from the pattern, with each wildcard replaced, so that many cases match.
  private def genValue(symbols: Vector[String], pattern: String, singleCharWildcard: Boolean): Gen[String] = {
    val literals = symbols.filterNot(s => s == "*" || s == "?")
    val instance = Gen
      .sequence[List[String], String](pattern.toList.map {
        case '*'                       => Gen.frequency(9 -> genString(literals, 4), 1 -> genString(literals, 1000))
        case '?' if singleCharWildcard => Gen.oneOf(literals)
        case c                         => Gen.const(c.toString)
      })
      .map(_.mkString)
    val mutated = for {
      value <- instance
      index <- Gen.choose(0, value.length)
      symbol <- Gen.oneOf(symbols)
      removed <- Gen.choose(0, if (index < value.length) 1 else 0)
    } yield value.patch(index, symbol, removed)
    Gen.frequency(
      2 -> instance,
      1 -> mutated,
      1 -> Gen.frequency(19 -> genString(symbols, 10), 1 -> genString(symbols, 1000))
    )
  }

  private def genString(symbols: Vector[String], maxLength: Int): Gen[String] =
    Gen.choose(0, maxLength).flatMap(Gen.listOfN(_, Gen.oneOf(symbols))).map(_.mkString)

  // Dynamic programming over pattern and value prefixes: simple, but its time is pattern length × value length.
  // A regex is not usable as reference: `.*` backtracks for minutes on long values.
  def referenceMatches(pattern: String, singleCharWildcard: Boolean, value: String): Boolean = {
    val initial = Array.tabulate(value.length + 1)(_ == 0)
    val last = pattern.foldLeft(initial) { (matched, p) =>
      val next = new Array[Boolean](value.length + 1)
      if (p == '*') {
        next(0) = matched(0)
        (1 to value.length).foreach(j => next(j) = matched(j) || next(j - 1))
      } else {
        (1 to value.length).foreach { j =>
          next(j) = matched(j - 1) && ((singleCharWildcard && p == '?') || p == value.charAt(j - 1))
        }
      }
      next
    }
    last(value.length)
  }

}

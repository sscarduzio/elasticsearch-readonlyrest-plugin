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

import cats.data.NonEmptyList
import cats.implicits.*
import org.scalacheck.{Arbitrary, Gen}
import org.scalactic.anyvals.{NonEmptyString, PosInt}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers.*
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import tech.beshu.ror.accesscontrol.domain.CaseSensitivity
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher.Matchable
import tech.beshu.ror.syntax.*

class MatcherWithWildcardsScalaTest
  extends AnyWordSpec
    with ScalaCheckDrivenPropertyChecks {

  import MatcherWithWildcardsScalaTest.*

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 1000, workers = PosInt.ensuringValid(Runtime.getRuntime.availableProcessors()))

  private val caseSensitiveStringMatchable: Matchable[String] = Matchable.matchable(identity, CaseSensitivity.Enabled)
  private val caseInsensitiveStringMatchable: Matchable[String] =  Matchable.matchable(identity, CaseSensitivity.Disabled)

  "match" should {
    "value matches pattern" in {
      forAll("pattern and value") { (patternAndValue: PatternAndMatch) =>
        val value = patternAndValue.value
        val pattern = patternAndValue.pattern
        val matcher = PatternsMatcher.create(List(pattern.string))(caseSensitiveStringMatchable)
        matcher.`match`(value.value) shouldBe true
      }
    }
    "value matches pattern case sensitive" in {
      forAll("CaseVulnerableString") { (caseVulnerableString: CaseVulnerableString) =>
        val value = caseVulnerableString.value.toLowerCase()
        val pattern = caseVulnerableString.value
        val matcher = PatternsMatcher.create(List(pattern))(caseSensitiveStringMatchable)
        matcher.`match`(value) shouldBe false
      }
    }
    "value matches pattern case insensitive" in {
      forAll("CaseVulnerableString") { (caseVulnerableString: CaseVulnerableString) =>
        val value = caseVulnerableString.value.toLowerCase()
        val pattern = caseVulnerableString.value
        val matcher = PatternsMatcher.create(List(pattern))(caseInsensitiveStringMatchable)
        matcher.`match`(value) shouldBe true
      }
    }
    "value mismatches matches empty" in {
      forAll("haystack") { (haystack: String) =>
        val matcher = PatternsMatcher.create(List.empty)(caseSensitiveStringMatchable)
        matcher.`match`(haystack) shouldBe false
      }
    }
    "haystack is pattern" in {
      forAll("haystack") { (haystack: String) =>
        val matcher = PatternsMatcher.create(List(haystack))(caseSensitiveStringMatchable)
        matcher.`match`(haystack) shouldBe true
      }
    }
    "haystack not in pattern" in {
      forAll("haystack", "pattern") { (haystack: String, patterns: NonEmptyList[String]) =>
        whenever(!patterns.toList.contains(haystack) && !patterns.toList.contains("*")) {
          val matcher = PatternsMatcher.create(patterns.toList)(caseSensitiveStringMatchable)
          matcher.`match`(haystack) shouldBe false
        }
      }
    }
    "haystack among patterns" in {
      forAll("haystack", "pattern") { (haystack: String, patterns: NonEmptyList[String]) =>
        val matcher = PatternsMatcher.create(haystack :: patterns.toList)(caseSensitiveStringMatchable)
        matcher.`match`(haystack) shouldBe true
      }
    }
    "pattern with many stars" in {
      forAll("starReplacements", "patternParts") { (starReplacements: Iterator[String], patternParts: NonEmptyList[String]) =>
        val iterator = starReplacements //.iterator
        val string = patternParts.reduceLeftOption((a, b) => s"$a${iterator.next()}$b").get
        val pattern = patternParts.reduceLeftOption((a, b) => s"$a*$b").get
        val matcher = PatternsMatcher.create(List(pattern))(caseSensitiveStringMatchable)
        matcher.`match`(string) shouldBe true
      }
    }
    "pattern starts with star" in {
      forAll("randomString", "pattern") { (randomString: String, pattern: String) =>
        val matcher = PatternsMatcher.create(List(s"*$pattern"))(caseSensitiveStringMatchable)
        matcher.`match`(s"$randomString$pattern") shouldBe true
      }
    }
    "pattern ends with star" in {
      forAll("randomString", "pattern") { (randomString: String, pattern: String) =>
        val matcher = PatternsMatcher.create(List(s"$pattern*"))(caseSensitiveStringMatchable)
        matcher.`match`(s"$pattern$randomString") shouldBe true
      }
    }
    "pattern with middle star" in {
      forAll("randomString", "pattern1", "pattern2") { (randomString: String, pattern1: String, pattern2: String) =>
        val matcher = PatternsMatcher.create(List(s"$pattern1*$pattern2"))(caseSensitiveStringMatchable)
        matcher.`match`(s"$pattern1$randomString$pattern2") shouldBe true
      }
    }
  }

  "filter result" should {
    "reject not related haystack" in {
      forAll("matchers", "haystack") { (matchers: List[String], haystack: Set[String]) =>
        val matcher = PatternsMatcher.create(matchers)(caseSensitiveStringMatchable)
        val result = matcher.filter(haystack)
        result shouldBe haystack.intersect(matchers.toCovariantSet)
      }
    }
    "contain all haystack if haystack is in matchers" in {
      forAll("matchers", "haystack") { (matchers: List[String], haystack: Set[String]) =>
        val matcher = PatternsMatcher.create(matchers ++ haystack)(caseSensitiveStringMatchable)
        val result = matcher.filter(haystack)
        result shouldBe haystack
      }
    }
    "should be empty if haystack is empty" in {
      forAll("matchers") { (matchers: List[String]) =>
        val matcher = PatternsMatcher.create(matchers)(caseSensitiveStringMatchable)
        val result = matcher.filter(Set.empty)
        result should be(empty)
      }
    }
    "should be empty if matchers is empty" in {
      forAll("haystack") { (haystack: Set[String]) =>
        val matcher = PatternsMatcher.create(Nil)(caseSensitiveStringMatchable)
        val result = matcher.filter(haystack)
        result should be(empty)
      }
    }
  }
  testMatchersShouldMatchHaystack(List("Banana*Apple"), "BananaPearApple")
  testMatchersShouldntMatchHaystack(List("Banana*Apple"), "PearBananaApple")
  testMatchersShouldntMatchHaystack(List("Banana*Apple"), "BananaApplePear")
  testMatchersShouldMatchHaystack(List("Banana*Apple"), "BananaApple")
  testMatchersShouldMatchHaystack(List("ab*d"), "abcd")
  testMatchersShouldMatchHaystack(List("abc*"), "abc")
  testMatchersShouldMatchHaystack(List("ab*"), "ab")
  testMatchersShouldMatchHaystack(List("ab"), "ab")
  testMatchersShouldntMatchHaystack(Nil, "")
  testMatchersShouldntMatchHaystack(Nil, "a")
  testMatchersShouldntMatchHaystack(List(""), "b")
  testMatchersShouldntMatchHaystack(List("a"), "")
  testMatchersShouldMatchHaystack(List("a"), "a")
  testMatchersShouldntMatchHaystack(List("ab"), "a")
  testMatchersShouldntMatchHaystack(List("a"), "ab")
  testMatchersShouldMatchHaystack(List("a*"), "ab")
  testMatchersShouldntMatchHaystack(List("A*"), "ab")
  testMatchersShouldntMatchHaystack(List("b"), "a")

  private def testMatchersShouldMatchHaystack(matchers: List[String], haystack: String): Unit =
    testMatchersMatchHaystack(matchers, haystack, result = true)

  private def testMatchersShouldntMatchHaystack(matchers: List[String], haystack: String): Unit =
    testMatchersMatchHaystack(matchers, haystack, result = false)

  private def testMatchersMatchHaystack(matchers: List[String], haystack: String, result: Boolean): Unit = {
    s"$haystack should${if (!result) "n't" else ""} match $matchers" in {
      val matcher = PatternsMatcher.create(matchers)(caseSensitiveStringMatchable)
      matcher.`match`(haystack) shouldBe result
    }
  }

}

object MatcherWithWildcardsScalaTest {
  final case class WildcardPattern(private val value: NonEmptyList[String]) {
    lazy val toList: List[String] = value.toList
    lazy val string: String = toList.mkString
  }
  final case class MatchingString(value: String) extends AnyVal
  final case class PatternAndMatch(pattern: WildcardPattern, value: MatchingString)
  final case class CaseVulnerableString(value: String)

  val genWildcardPattern: Gen[WildcardPattern] = {
    Gen.someOf(Gen.asciiPrintableStr, Gen.const("*"))
      .map(_.toList.toNel)
      .filter(_.isDefined)
      .map(_.get)
      .map(WildcardPattern.apply)
  }

  def genLiteralString(wildcardPattern: WildcardPattern,
                       genString: Gen[String]): Gen[MatchingString] = {
    for {
      stream <- Gen.infiniteLazyList(genString)
      iterator = stream.iterator
      str = wildcardPattern.toList
        .map {
          case "*" => iterator.next()
          case a => a
        }.mkString
    } yield MatchingString(str)
  }

  val genPatternAndValue: Gen[PatternAndMatch] =
    for {
      pattern <- genWildcardPattern
      value <- genLiteralString(pattern, Gen.asciiPrintableStr)
    } yield PatternAndMatch(pattern, value)

  implicit val arbPatternAndValue: Arbitrary[PatternAndMatch] = Arbitrary(genPatternAndValue)

  implicit val arbCaseVulnerableString: Arbitrary[CaseVulnerableString] =
    Arbitrary(Gen.asciiPrintableStr.filter(s => s.neqv(s.toLowerCase())).map(CaseVulnerableString.apply))

  implicit def arbIterator[A](implicit arb: Arbitrary[A]): Arbitrary[Iterator[A]] =
    Arbitrary(Gen.infiniteStream(arb.arbitrary).map(_.iterator))

  implicit def arbNonEmptyList[A](implicit arb: Arbitrary[List[A]]): Arbitrary[NonEmptyList[A]] =
    Arbitrary(arb.arbitrary.filter(_.nonEmpty).map(_.toNel.get))

  implicit def arbNonEmptyString(implicit arb: Arbitrary[String]): Arbitrary[NonEmptyString] =
    Arbitrary(arb.arbitrary.filter(_.nonEmpty).map(NonEmptyString.apply))
}

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

import cats.data.NonEmptyList
import cats.implicits._
import org.scalacheck.{Arbitrary, Gen}
import org.scalactic.anyvals.{NonEmptyString, PosInt}
import org.scalatest.wordspec.AnyWordSpec
import org.scalatestplus.scalacheck.ScalaCheckDrivenPropertyChecks
import tech.beshu.ror.utils.MatcherWithWildcardsTest._

import scala.jdk.CollectionConverters._

class MatcherWithWildcardsTest
  extends AnyWordSpec
    with ScalaCheckDrivenPropertyChecks {

  import org.scalatest.matchers.should.Matchers._

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 1000, workers = PosInt.ensuringValid(Runtime.getRuntime.availableProcessors()))
  private val caseSensitive = CaseMappingEquality.summonJava(StringCaseMapping.caseSensitiveEquality)
  private val caseInsensitive = CaseMappingEquality.summonJava(StringCaseMapping.caseInsensitiveEquality)

  "match" should {
    "value matches pattern" in {
      forAll("pattern and value") { (patternAndValue: PatternAndMatch) =>
        val value = patternAndValue.value
        val pattern = patternAndValue.pattern
        val matcher = new MatcherWithWildcardsJava(List(pattern.string).asJava, caseSensitive)
        matcher.`match`(value.value) shouldBe true
      }
    }
    "value matches pattern case sensitive" in {
      forAll("CaseVulnerableString") { (caseVulnerableString: CaseVulnerableString) =>
        val value = caseVulnerableString.value.toLowerCase()
        val pattern = caseVulnerableString.value
        val matcher = new MatcherWithWildcardsJava(List(pattern).asJava, caseSensitive)
        matcher.`match`(value) shouldBe false
      }
    }
    "value matches pattern case insensitive" in {
      forAll("CaseVulnerableString") { (caseVulnerableString: CaseVulnerableString) =>
        val value = caseVulnerableString.value.toLowerCase()
        val pattern = caseVulnerableString.value
        val matcher = new MatcherWithWildcardsJava(List(pattern).asJava, caseInsensitive)
        matcher.`match`(value) shouldBe true
      }
    }
    "value mismatches matches empty" in {
      forAll("haystack") { (haystack: String) =>
        val matcher = new MatcherWithWildcardsJava(List().asJava, caseSensitive)
        matcher.`match`(haystack) shouldBe false
      }
    }
    "haystack is pattern" in {
      forAll("haystack") { (haystack: String) =>
        val matcher = new MatcherWithWildcardsJava(List(haystack).asJava, caseSensitive)
        matcher.`match`(haystack) shouldBe true
      }
    }
    "haystack not in pattern" in {
      forAll("haystack", "pattern") { (haystack: String, patterns: NonEmptyList[String]) =>
        whenever(!patterns.toList.contains(haystack) && !patterns.toList.contains("*")) {
          val matcher = new MatcherWithWildcardsJava(patterns.toList.asJava, caseSensitive)
          matcher.`match`(haystack) shouldBe false
        }
      }
    }
    "haystack among patterns" in {
      forAll("haystack", "pattern") { (haystack: String, patterns: NonEmptyList[String]) =>
        val matcher = new MatcherWithWildcardsJava((haystack :: patterns.toList).asJava, caseSensitive)
        matcher.`match`(haystack) shouldBe true
      }
    }
    "pattern with many stars" in {
      forAll("starReplacements", "patternParts") { (starReplacements: Iterator[String], patternParts: NonEmptyList[String]) =>
        val iterator = starReplacements //.iterator
        val string = patternParts.reduceLeftOption((a, b) => s"$a${iterator.next()}$b").get
        val pattern = patternParts.reduceLeftOption((a, b) => s"$a*$b").get
        val matcher = new MatcherWithWildcardsJava(List(pattern).asJava, caseSensitive)
        matcher.`match`(string) shouldBe true
      }
    }
    "pattern starts with star" in {
      forAll("randomString", "pattern") { (randomString: String, pattern: String) =>
        val matcher = new MatcherWithWildcardsJava(List(s"*$pattern").asJava, caseSensitive)
        matcher.`match`(s"$randomString$pattern") shouldBe true
      }
    }
    "pattern ends with star" in {
      forAll("randomString", "pattern") { (randomString: String, pattern: String) =>
        val matcher = new MatcherWithWildcardsJava(List(s"$pattern*").asJava, caseSensitive)
        matcher.`match`(s"$pattern$randomString") shouldBe true
      }
    }
    "pattern with middle star" in {
      forAll("randomString", "pattern1", "pattern2") { (randomString: String, pattern1: String, pattern2: String) =>
        val matcher = new MatcherWithWildcardsJava(List(s"$pattern1*$pattern2").asJava, caseSensitive)
        matcher.`match`(s"$pattern1$randomString$pattern2") shouldBe true
      }
    }
  }

  "a test" in {
    val matchers: List[String] = List("*")
    val haystack: Set[String] = Set()
    val matcher = new MatcherWithWildcardsJava(matchers.asJava, caseSensitive)
    val result = matcher.filter(haystack.asJava).asScala.toSet
    result shouldBe haystack.intersect(matchers.toSet)
  }
  "filter result" should {
    "reject not related haystach" in {
      forAll("matchers", "haystack") { (matchers: List[String], haystack: Set[String]) =>
        val matcher = new MatcherWithWildcardsJava(matchers.asJava, caseSensitive)
        val result = matcher.filter(haystack.asJava).asScala.toSet
        result shouldBe haystack.intersect(matchers.toSet)
      }
    }
    "contain all haystack if haystack is in matchers" in {
      forAll("matchers", "haystack") { (matchers: List[String], haystack: Set[String]) =>
        val matcher = new MatcherWithWildcardsJava((matchers ++ haystack).asJava, caseSensitive)
        val result = matcher.filter(haystack.asJava).asScala.toSet
        result shouldBe haystack
      }
    }
    "should be empty if haystack is empty" in {
      forAll("matchers") { (matchers: List[String]) =>
        val matcher = new MatcherWithWildcardsJava(matchers.asJava, caseSensitive)
        val result = matcher.filter(Set().asJava).asScala.toSet
        result should be(empty)
      }
    }
    "should be empty if matchers is empty" in {
      forAll("haystack") { (haystack: Set[String]) =>
        val matcher = new MatcherWithWildcardsJava(Nil.asJava, caseSensitive)
        val result = matcher.filter(haystack.asJava).asScala.toSet
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
    testMatchersMatchHaystack(matchers, haystack, true)

  private def testMatchersShouldntMatchHaystack(matchers: List[String], haystack: String): Unit =
    testMatchersMatchHaystack(matchers, haystack, false)

  private def testMatchersMatchHaystack(matchers: List[String], haystack: String, result: Boolean): Unit = {
    s"$haystack should${if (!result) "n't"} match $matchers" in {
      val matcher = new MatcherWithWildcardsJava(matchers.asJava, caseSensitive)
      matcher.`match`(haystack) shouldBe result
    }
  }

}
object MatcherWithWildcardsTest {
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
      .map(WildcardPattern)
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
    Arbitrary(Gen.asciiPrintableStr.filter(s => s.neqv(s.toLowerCase())).map(CaseVulnerableString))

  implicit def arbIterator[A](implicit arb: Arbitrary[A]): Arbitrary[Iterator[A]] =
    Arbitrary(Gen.infiniteStream(arb.arbitrary).map(_.iterator))

  implicit def arbNonEmptyList[A](implicit arb: Arbitrary[List[A]]): Arbitrary[NonEmptyList[A]] =
    Arbitrary(arb.arbitrary.filter(_.nonEmpty).map(_.toNel.get))

  implicit def arbNonEmptyString(implicit arb: Arbitrary[String]): Arbitrary[NonEmptyString] =
    Arbitrary(arb.arbitrary.filter(_.nonEmpty).map(NonEmptyString.apply))
}

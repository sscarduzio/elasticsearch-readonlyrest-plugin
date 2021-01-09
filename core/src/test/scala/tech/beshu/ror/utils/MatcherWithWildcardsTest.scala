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

import scala.collection.JavaConverters._

class MatcherWithWildcardsTest
  extends AnyWordSpec
    with ScalaCheckDrivenPropertyChecks {

  import org.scalatest.matchers.should.Matchers._

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 1000, workers = PosInt.ensuringValid(Runtime.getRuntime.availableProcessors()))
  "matchRemoteClusterAware" should {
    "value matches pattern" in {
      forAll("pattern and value") { (patternAndValue: PatternAndValue) =>
        val value = patternAndValue.value
        val pattern = patternAndValue.pattern
        val matcher = new MatcherWithWildcards(List(pattern.string).asJava)
        matcher.matchRemoteClusterAware(value.value) shouldBe true
      }
    }

    "value mismatches matches empty" in {
      forAll("haystack") { (haystack: String) =>
        val matcher = new MatcherWithWildcards(List().asJava)
        matcher.matchRemoteClusterAware(haystack) shouldBe false
      }
    }
    "haystack is pattern" in {
      forAll("haystack") { (haystack: String) =>
        val matcher = new MatcherWithWildcards(List(haystack).asJava)
        matcher.matchRemoteClusterAware(haystack) shouldBe true
      }
    }
    "haystack is empty" in {
      forAll("pattern") { (pattern: NonEmptyString) =>
        val matcher = new MatcherWithWildcards(List(pattern.theString).asJava)
        matcher.matchRemoteClusterAware("") shouldBe false
      }
    }
    "haystack not in pattern" in {
      forAll("haystack", "pattern") { (haystack: String, patterns: NonEmptyList[String]) =>
        whenever(!patterns.toList.contains(haystack) && !patterns.toList.contains("*")) {
          val matcher = new MatcherWithWildcards(patterns.toList.asJava)
          matcher.matchRemoteClusterAware(haystack) shouldBe false
        }
      }
    }
    "haystack among patterns" in {
      forAll("haystack", "pattern") { (haystack: String, patterns: NonEmptyList[String]) =>
        val matcher = new MatcherWithWildcards((haystack :: patterns.toList).asJava)
        matcher.matchRemoteClusterAware(haystack) shouldBe true
      }
    }
    "pattern with many stars" in {
      forAll("starReplacements", "patternParts") { (starReplacements: Iterator[String], patternParts: NonEmptyList[String]) =>
        val iterator = starReplacements //.iterator
        val string = patternParts.reduceLeftOption((a, b) => s"$a${iterator.next()}$b").get
        val pattern = patternParts.reduceLeftOption((a, b) => s"$a*$b").get
        val matcher = new MatcherWithWildcards(List(pattern).asJava)
        matcher.matchRemoteClusterAware(string) shouldBe true
      }
    }
    "pattern starts with star" in {
      forAll("randomString", "pattern") { (randomString: String, pattern: String) =>
        val matcher = new MatcherWithWildcards(List(s"*$pattern").asJava)
        matcher.matchRemoteClusterAware(s"$randomString$pattern") shouldBe true
      }
    }
    "pattern ends with star" in {
      forAll("randomString", "pattern") { (randomString: String, pattern: String) =>
        val matcher = new MatcherWithWildcards(List(s"$pattern*").asJava)
        matcher.matchRemoteClusterAware(s"$pattern$randomString") shouldBe true
      }
    }
    "pattern with middle star" in {
      forAll("randomString", "pattern1", "pattern2") { (randomString: String, pattern1: String, pattern2: String) =>
        val matcher = new MatcherWithWildcards(List(s"$pattern1*$pattern2").asJava)
        matcher.matchRemoteClusterAware(s"$pattern1$randomString$pattern2") shouldBe true
      }
    }
  }
  "match" should {
    "value matches pattern" in {
      forAll("pattern and value") { (patternAndValue: PatternAndValue) =>
        val value = patternAndValue.value
        val pattern = patternAndValue.pattern
        val matcher = new MatcherWithWildcards(List(pattern.string).asJava)
        matcher.`match`(value.value) shouldBe true
      }
    }

    "value mismatches matches empty" in {
      forAll("haystack") { (haystack: String) =>
        val matcher = new MatcherWithWildcards(List().asJava)
        matcher.`match`(haystack) shouldBe false
      }
    }
    "haystack is pattern" in {
      forAll("haystack") { (haystack: String) =>
        val matcher = new MatcherWithWildcards(List(haystack).asJava)
        matcher.`match`(haystack) shouldBe true
      }
    }
    "haystack not in pattern" in {
      forAll("haystack", "pattern") { (haystack: String, patterns: NonEmptyList[String]) =>
        whenever(!patterns.toList.contains(haystack) && !patterns.toList.contains("*")) {
          val matcher = new MatcherWithWildcards(patterns.toList.asJava)
          matcher.`match`(haystack) shouldBe false
        }
      }
    }
    "haystack among patterns" in {
      forAll("haystack", "pattern") { (haystack: String, patterns: NonEmptyList[String]) =>
        val matcher = new MatcherWithWildcards((haystack :: patterns.toList).asJava)
        matcher.`match`(haystack) shouldBe true
      }
    }
    "pattern with many stars" in {
      forAll("starReplacements", "patternParts") { (starReplacements: Iterator[String], patternParts: NonEmptyList[String]) =>
        val iterator = starReplacements //.iterator
        val string = patternParts.reduceLeftOption((a, b) => s"$a${iterator.next()}$b").get
        val pattern = patternParts.reduceLeftOption((a, b) => s"$a*$b").get
        val matcher = new MatcherWithWildcards(List(pattern).asJava)
        matcher.`match`(string) shouldBe true
      }
    }
    "pattern starts with star" in {
      forAll("randomString", "pattern") { (randomString: String, pattern: String) =>
        val matcher = new MatcherWithWildcards(List(s"*$pattern").asJava)
        matcher.`match`(s"$randomString$pattern") shouldBe true
      }
    }
    "pattern ends with star" in {
      forAll("randomString", "pattern") { (randomString: String, pattern: String) =>
        val matcher = new MatcherWithWildcards(List(s"$pattern*").asJava)
        matcher.`match`(s"$pattern$randomString") shouldBe true
      }
    }
    "pattern with middle star" in {
      forAll("randomString", "pattern1", "pattern2") { (randomString: String, pattern1: String, pattern2: String) =>
        val matcher = new MatcherWithWildcards(List(s"$pattern1*$pattern2").asJava)
        matcher.`match`(s"$pattern1$randomString$pattern2") shouldBe true
      }
    }
  }
  "filter result" should {
    "reject not related haystach" in {
      forAll("matchers", "haystack") { (matchers: List[String], haystack: Set[String]) =>
        val matcher = new MatcherWithWildcards(matchers.asJava)
        val result = matcher.filter(haystack.asJava).asScala.toSet
        result shouldBe haystack.intersect(matchers.toSet)
      }
    }
    "contain all haystack if haystack is in matchers" in {
      forAll("matchers", "haystack") { (matchers: List[String], haystack: Set[String]) =>
        val matcher = new MatcherWithWildcards((matchers ++ haystack).asJava)
        val result = matcher.filter(haystack.asJava).asScala.toSet
        result shouldBe haystack
      }
    }
    "should be empty if haystack is empty" in {
      forAll("matchers") { (matchers: List[String]) =>
        val matcher = new MatcherWithWildcards(matchers.asJava)
        val result = matcher.filter(Set().asJava).asScala.toSet
        result should be(empty)
      }
    }
    "should be empty if matchers is empty" in {
      forAll("haystack") { (haystack: Set[String]) =>
        val matcher = new MatcherWithWildcards(Nil.asJava)
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

  private def testMatchersShouldMatchHaystack(matchers: List[String], haystack: String) =
    testMatchersMatchHaystack(matchers, haystack, true)

  private def testMatchersShouldntMatchHaystack(matchers: List[String], haystack: String) =
    testMatchersMatchHaystack(matchers, haystack, false)

  private def testMatchersMatchHaystack(matchers: List[String], haystack: String, result: Boolean) = {
    s"$haystack should${if (!result) "n't"} match $matchers" in {
      val matcher = new MatcherWithWildcards(matchers.asJava)
      matcher.`match`(haystack) shouldBe result
    }
  }

}
object MatcherWithWildcardsTest {
  final case class WildcardPattern(private val value: NonEmptyList[String]) {
    def toList = value.toList

    def string = toList.mkString
  }
  final case class LiteralString(value: String) extends AnyVal
  final case class PatternAndValue(pattern: WildcardPattern, value: LiteralString)
  implicit val genWildcardPattern: Gen[WildcardPattern] = {

    Gen.someOf(Gen.asciiPrintableStr, Gen.const("*"))
      .map(_.toList.toNel)
      .filter(_.isDefined)
      .map(_.get)
      .map(WildcardPattern)
  }

  def genLiteralString(wildcardPattern: WildcardPattern,
                       genString: Gen[String]): Gen[LiteralString] = {
    for {
      stream <- Gen.infiniteStream(genString)
      iterator = stream.iterator
      str = wildcardPattern.toList
        .map {
          case "*" => iterator.next()
          case a => a
        }.mkString
    } yield LiteralString(str)
  }

  implicit val genPatternAndValue: Gen[PatternAndValue] =
    for {
      pattern <- implicitly[Gen[WildcardPattern]]
      value <- genLiteralString(pattern, Gen.asciiPrintableStr)
    } yield PatternAndValue(pattern, value)
  implicit val arbPatternAndValue: Arbitrary[PatternAndValue] = Arbitrary(genPatternAndValue)

  implicit def arbIterator[A](implicit arb: Arbitrary[A]): Arbitrary[Iterator[A]] =
    Arbitrary(Gen.infiniteStream(arb.arbitrary).map(_.iterator))

  implicit def arbNEL[A](implicit arb: Arbitrary[List[A]]): Arbitrary[NonEmptyList[A]] =
    Arbitrary(arb.arbitrary.filter(_.nonEmpty).map(_.toNel.get))

  implicit def arbNES(implicit arb: Arbitrary[String]): Arbitrary[NonEmptyString] =
    Arbitrary(arb.arbitrary.filter(_.nonEmpty).map(NonEmptyString.apply))
}

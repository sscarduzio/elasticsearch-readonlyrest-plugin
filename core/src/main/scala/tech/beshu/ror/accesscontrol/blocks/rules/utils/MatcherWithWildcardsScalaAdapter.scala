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
package tech.beshu.ror.accesscontrol.blocks.rules.utils

import cats.Show
import cats.implicits._
import tech.beshu.ror.utils.{CaseMappingEquality, MatcherWithWildcards, StringMatcherWithWildcards}

import scala.collection.JavaConverters._

trait Matcher[A] {
  def underlying: MatcherWithWildcards[A]

  def filter(items: Set[A]): Set[A]

  def `match`(value: A): Boolean

  def contains(str: String): Boolean
}
object Matcher {
  def asMatcherWithWildcards[A](matcher: Matcher[A]): StringMatcherWithWildcards = {
    new StringMatcherWithWildcards(matcher.underlying.getMatchers)
  }

}

class MatcherWithWildcardsScalaAdapter[A](override val underlying: MatcherWithWildcards[A])
  extends Matcher[A] {

  override def filter(items: Set[A]): Set[A] = {
    underlying
      .filter(items.asJava)
      .asScala
      .toSet
  }

  override def `match`(value: A): Boolean = {
    underlying.`match`(value)
  }

  override def contains(str: String): Boolean =
    underlying.getMatchers.contains(str)
}

object MatcherWithWildcardsScalaAdapter {

  def create[T: CaseMappingEquality](items: Iterable[T]): Matcher[T] =
    new MatcherWithWildcardsScalaAdapter(new MatcherWithWildcards(items.map(Show[T].show).asJava, CaseMappingEquality.summonJava))

  def apply[A: CaseMappingEquality](patterns: Set[A]): MatcherWithWildcardsScalaAdapter[A] =
    fromJavaSetString(patterns.map(_.show).asJava)

  def fromJavaSetString[A: CaseMappingEquality](patterns: java.util.Set[String]): MatcherWithWildcardsScalaAdapter[A] =
    new MatcherWithWildcardsScalaAdapter(new MatcherWithWildcards[A](patterns, CaseMappingEquality.summonJava))

  def fromSetString[A: CaseMappingEquality](patterns: Set[String]): MatcherWithWildcardsScalaAdapter[A] =
    fromJavaSetString(patterns.asJava)

  def isMatched(pattern: String, value: String): Boolean =
    new StringMatcherWithWildcards(List(pattern).asJava).`match`(value)
}

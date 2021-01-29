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
import tech.beshu.ror.utils.{CaseMappingEquality, GenericMatcherWithWildcards, MatcherWithWildcards}

import scala.collection.JavaConverters._

trait Matcher[A] {
  def underlying: GenericMatcherWithWildcards[A]

  def filter(items: Set[A]): Set[A]

  def `match`(value: A): Boolean

  def contains(str: String): Boolean
}
object Matcher {
  def asMatcherWithWildcards[A](matcher: Matcher[A]): MatcherWithWildcards = {
    new MatcherWithWildcards(matcher.underlying.getMatchers)
  }

}

class MatcherWithWildcardsScalaAdapter[A](override val underlying: GenericMatcherWithWildcards[A])
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

  import tech.beshu.ror.utils.CaseMappingEquality._

  def create[T: CaseMappingEquality](items: Iterable[T]): Matcher[T] =
    new MatcherWithWildcardsScalaAdapter(new GenericMatcherWithWildcards(items.map(Show[T].show).asJava, CaseMappingEquality.summonJava))

  def apply[A: CaseMappingEquality](patterns: Set[A]): MatcherWithWildcardsScalaAdapter[A] =
    fromJavaSetString(patterns.map(_.show).asJava)

  def fromJavaSetString[A: CaseMappingEquality](patterns: java.util.Set[String]): MatcherWithWildcardsScalaAdapter[A] =
    new MatcherWithWildcardsScalaAdapter(new GenericMatcherWithWildcards[A](patterns, CaseMappingEquality.summonJava))

  def fromSetString[A: CaseMappingEquality](patterns: Set[String]): MatcherWithWildcardsScalaAdapter[A] =
    fromJavaSetString(patterns.asJava)

  def fromJavaSet[A: CaseMappingEquality](patterns: java.util.Set[A]): MatcherWithWildcardsScalaAdapter[A] =
    fromJavaSetString(patterns.asScala.toSet.map(CaseMappingEquality[A].show.show).asJava)

  def isMatched(pattern: String, value: String): Boolean =
    new MatcherWithWildcards(List(pattern).asJava).`match`(value)
}

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
package tech.beshu.ror.accesscontrol.matchers

import tech.beshu.ror.accesscontrol.domain.GlobPattern

// todo: rename
trait Matcher[A] {
  type Conversion[B] = Function1[B, A]

  def globPatterns: Iterable[GlobPattern]

  def filter[B <: A](items: Iterable[B]): Set[B]

  def filter[B: Conversion](items: Iterable[B]): Set[B]

  def `match`[B <: A](value: B): Boolean

  def contains(str: String): Boolean
}
object Matcher {

  object Conversion {
    def from[B, A](func: B => A): Matcher[A]#Conversion[B] = (a: B) => func(a)
  }
}

// todo: remove
//class MatcherWithWildcardsScala[A](override val underlying: MatcherWithWildcardsScala[A])
//  extends Matcher[A] {
//
//  override def filter[B <: A](items: Set[B]): Set[B] =
//    underlying.filter(items)
//
//  override def filter[B: Conversion](items: Set[B]): Set[B] = {

//  }
//
//  override def `match`[B <: A](value: B): Boolean =
//    underlying.`match`(value)
//
//  override def contains(str: String): Boolean =
//    underlying.globPatterns.exists(_.pattern.value == str)
//
//}
//
//object MatcherWithWildcardsScala {
//
//  // todo: remove
////  def create[A](items: Iterable[A]): Matcher[A] =
////    new MatcherWithWildcardsScala(new MatcherWithWildcardsScala(items.map(Show[T].show)))
//
//  def apply[A : Matchable](patterns: Set[A]): MatcherWithWildcardsScala[A] =
//    fromJavaSetString(patterns.map(_.show).asJava)
//
//  // todo: remove
//  def fromJavaSetString[A](patterns: java.util.Set[String]): MatcherWithWildcardsScala[A] =
//    new MatcherWithWildcardsScala(new MatcherWithWildcardsScala[A](patterns.asScala))
//
//  def fromSetString[A](patterns: Set[String]): MatcherWithWildcardsScala[A] =
//    fromJavaSetString(patterns.asJava)
//
//  // todo: remove
////  def isMatched(pattern: String, value: String): Boolean =
////    new StringMatcherWithWildcards(List(pattern).asJava).`match`(value)
//}

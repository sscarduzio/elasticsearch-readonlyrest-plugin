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

import cats.Show
import tech.beshu.ror.accesscontrol.domain.CaseSensitivity

trait PatternsMatcher[A] {
  type Conversion[B] = Function1[B, A]

  def caseSensitivity: CaseSensitivity
  def patterns: Iterable[String]

  def `match`[B <: A](value: B): Boolean

  def `match`[B : Conversion](value: B): Boolean

  def filter[B <: A](items: Iterable[B]): Set[B]

  def filter[B: Conversion](items: Iterable[B]): Set[B]

  def contains(str: String): Boolean
}
object PatternsMatcher {

  object Conversion {
    def from[B, A](func: B => A): PatternsMatcher[A]#Conversion[B] = (a: B) => func(a)
  }

  def create[T : Matchable](values: Iterable[T]): PatternsMatcher[T] =
    new GlobPatternsMatcher[T](values)

  trait Matchable[T] extends Show[T] {
    def caseSensitivity: CaseSensitivity
  }
  object Matchable {

    def apply[A](implicit instance: Matchable[A]): Matchable[A] = instance

    def matchable[A](f: A => String,
                     aCaseSensitivity: CaseSensitivity = CaseSensitivity.Enabled): Matchable[A] = new Matchable[A] {
      override def show(t: A): String = f(t)

      override def caseSensitivity: CaseSensitivity = aCaseSensitivity
    }

    val caseSensitiveStringMatchable: Matchable[String] = Matchable.matchable(identity, CaseSensitivity.Enabled)
    val caseInsensitiveStringMatchable: Matchable[String] = Matchable.matchable(identity, CaseSensitivity.Disabled)
  }
}


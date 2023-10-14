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

import cats.Show
import com.hrakaroo.glob.{GlobPattern => Glob}
import tech.beshu.ror.accesscontrol.domain.GlobPattern
import tech.beshu.ror.accesscontrol.domain.GlobPattern.CaseSensitivity
import tech.beshu.ror.accesscontrol.matchers.Matcher

import scala.language.implicitConversions

class MatcherWithWildcardsScala[T : Matchable](val values: Iterable[T])
 extends Matcher[T] {

  override val globPatterns: Iterable[GlobPattern] = {
    val matchable = implicitly[Matchable[T]]
    values.map(matchable.toGlobPattern)
  }

  private val globs = globPatterns.map { p =>
    Glob.compile(p.pattern, '*', '?', globPatternFlags(p))
  }

  override def `match`[B <: T](value: B): Boolean = {
    globs.exists(_.matches(Matchable[T].show(value)))
  }

  override def filter[B <: T](items: Iterable[B]): Set[B] = {
    items.toSet.filter(`match`)
  }

  // todo: do we need it?
  override def filter[B: Conversion](items: Iterable[B]): Set[B] = {
    val bToAConversion = implicitly[Conversion[B]]
    items
      .flatMap {
        case b if `match`(bToAConversion(b)) => Some(b)
        case _ => None
      }
      .toSet
  }

  // todo: do we need it?
  override def contains(str: String): Boolean = {
    val matchable = implicitly[Matchable[T]]
    values.map(matchable.show).exists(_ == str)
  }

  private def globPatternFlags(p: GlobPattern) = {
    p.caseSensitivity match {
      case CaseSensitivity.Enabled => Glob.HANDLE_ESCAPES
      case CaseSensitivity.Disabled => Glob.CASE_INSENSITIVE & Glob.HANDLE_ESCAPES
    }
  }
}

object MatcherWithWildcardsScala {
  def create[T : Matchable](values: Iterable[T]): MatcherWithWildcardsScala[T] =
    new MatcherWithWildcardsScala[T](values)
}

trait Matchable[T] extends Show[T] {
  implicit def toGlobPattern(value: T): GlobPattern
}
object Matchable {

  def apply[A](implicit instance: Matchable[A]): Matchable[A] = instance

  def matchable[A](f: A => String,
                   caseSensitivity: CaseSensitivity = CaseSensitivity.Enabled): Matchable[A] = new Matchable[A] {
    override def show(t: A): String = f(t)
    override implicit def toGlobPattern(value: A): GlobPattern = GlobPattern(f(value), caseSensitivity)
  }

  implicit val stringMatchable: Matchable[String] = Matchable.matchable(identity)
}
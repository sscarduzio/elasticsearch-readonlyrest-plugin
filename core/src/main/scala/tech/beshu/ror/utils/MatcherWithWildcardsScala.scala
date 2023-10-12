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

class MatcherWithWildcardsScala[T : Matchable](val globPatterns: Iterable[GlobPattern]) {

  private val globs = globPatterns.map(p =>
    Glob.compile(p.pattern.value, '*', '?', globPatternFlags(p))
  )

  def filter[S <: T](values: Set[S]): Set[S] = {
    values.filter(`match`)
  }

  def `match`(value: T): Boolean = {
    globs.exists(_.matches(Matchable[T].show(value)))
  }

  private def globPatternFlags(p: GlobPattern) = {
    p.caseSensitivity match {
      case CaseSensitivity.Enabled => Glob.HANDLE_ESCAPES
      case CaseSensitivity.Disabled => Glob.CASE_INSENSITIVE & Glob.HANDLE_ESCAPES
    }
  }
}

trait Matchable[T] extends Show[T]
object Matchable {

  def apply[A](implicit instance: Matchable[A]): Matchable[A] = instance

  def matchable[A](f: A => String): Matchable[A] = f(_)

}
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
import com.hrakaroo.glob.GlobPattern
import tech.beshu.ror.accesscontrol.domain.CaseSensitivity

private[matchers] class GlobPatternsMatcher[T: Matchable](val values: Iterable[T])
  extends PatternsMatcher[T] {

  override val patterns: Iterable[String] = values.map(implicitly[Matchable[T]].show)
  override val caseSensitivity: CaseSensitivity = implicitly[Matchable[T]].caseSensitivity

  private val globs = values.map { v =>
    val matchable = implicitly[Matchable[T]]
    GlobPattern.compile(matchable.show(v), '*', '?', globPatternFlags(matchable.caseSensitivity))
  }

  override def `match`[B <: T](value: B): Boolean = {
    globs.exists(_.matches(Matchable[T].show(value)))
  }

  override def filter[B <: T](items: Iterable[B]): Set[B] = {
    items.toSet.filter(`match`)
  }

  override def filter[B: Conversion](items: Iterable[B]): Set[B] = {
    val bToTConversion = implicitly[Conversion[B]]
    items
      .flatMap {
        case b if `match`(bToTConversion(b)) => Some(b)
        case _ => None
      }
      .toSet
  }

  override def contains(str: String): Boolean = {
    values
      .map(implicitly[Matchable[T]].show)
      .exists(_ == str)
  }

  private def globPatternFlags(caseSensitivity: CaseSensitivity) = {
    // todo: in the future we can handle escapes too
    caseSensitivity match {
      case CaseSensitivity.Enabled => 0
      case CaseSensitivity.Disabled => GlobPattern.CASE_INSENSITIVE
    }
  }

}

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
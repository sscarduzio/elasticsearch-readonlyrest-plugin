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

import com.hrakaroo.glob.GlobPattern
import tech.beshu.ror.accesscontrol.domain.CaseSensitivity
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher.Matchable
import tech.beshu.ror.syntax.*

private[matchers] class GlobPatternsMatcher[A: Matchable](val values: Iterable[A])
  extends PatternsMatcher[A] {

  override val patterns: Iterable[String] = values.map(implicitly[Matchable[A]].show)
  override val caseSensitivity: CaseSensitivity = implicitly[Matchable[A]].caseSensitivity

  private val globs = values.map { v =>
    val matchable = implicitly[Matchable[A]]
    GlobPattern.compile(matchable.show(v), '*', '?', globPatternFlags(matchable.caseSensitivity))
  }

  override def `match`[B <: A](value: B): Boolean = {
    globs.exists(_.matches(Matchable[A].show(value)))
  }

  override def `match`[B: Conversion](value: B): Boolean = {
    val bAoAConversion = implicitly[Conversion[B]]
    `match`(bAoAConversion(value))
  }

  override def filter[B <: A](items: IterableOnce[B]): Set[B] = {
    filterWithConversion(items, identity)
  }

  override def filter[B: Conversion](items: IterableOnce[B]): Set[B] = {
    filterWithConversion(items, implicitly[Conversion[B]])
  }

  override def contains(str: String): Boolean = {
    values
      .map(implicitly[Matchable[A]].show)
      .exists(_ == str)
  }

  private def filterWithConversion[B](items: IterableOnce[B], conversion: Conversion[B]): Set[B] = {
    items.iterator
      .flatMap {
        case b if `match`(conversion(b)) => Some(b)
        case _ => None
      }
      .toCovariantSet
  }

  private def globPatternFlags(caseSensitivity: CaseSensitivity) = {
    // todo: in the future we can handle escapes too
    caseSensitivity match {
      case CaseSensitivity.Enabled => 0
      case CaseSensitivity.Disabled => GlobPattern.CASE_INSENSITIVE
    }
  }

}

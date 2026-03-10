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

import cats.data.NonEmptyList
import tech.beshu.ror.accesscontrol.domain.CaseSensitivity
import tech.beshu.ror.syntax.*

private[matchers] class CombinedPatternsMatcher[A](val matchers: NonEmptyList[PatternsMatcher[A]])
  extends PatternsMatcher[A] {

  override val caseSensitivity: CaseSensitivity = matchers.head.caseSensitivity
  override val patterns: Iterable[String] = matchers.toList.flatMap(_.patterns)

  override def `match`[B <: A](value: B): Boolean =
    matchers.exists(_.`match`(value))

  override def `match`[B: Conversion](value: B): Boolean =
    matchers.exists(m => m.`match`(value))

  override def filter[B <: A](items: IterableOnce[B]): Set[B] = {
    matchers.toList.flatMap(_.filter(items)).toCovariantSet
  }

  override def filter[B: Conversion](items: IterableOnce[B]): Set[B] = {
    matchers.toList.flatMap(m => m.filter(items)).toCovariantSet
  }

  override def contains(str: String): Boolean =
    matchers.exists(_.contains(str))
}

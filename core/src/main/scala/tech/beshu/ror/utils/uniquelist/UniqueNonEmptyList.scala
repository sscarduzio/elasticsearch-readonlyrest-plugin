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
package tech.beshu.ror.utils.uniquelist

import cats.data.NonEmptyList
import cats.implicits.*
import tech.beshu.ror.utils.set.CovariantSet

final case class UniqueNonEmptyList[+T] private[uniquelist](private val underlying: UniqueList[T])
  extends Iterable[T] {

  override def iterator: Iterator[T] = underlying.iterator
}

object UniqueNonEmptyList {

  def fromNonEmptyList[T](nel: NonEmptyList[T]): UniqueNonEmptyList[T] = unsafeFrom(nel.toList)

  def from[T](iterable: Iterable[T]): Option[UniqueNonEmptyList[T]] =
    if (iterable.isEmpty) None
    else Some(UniqueNonEmptyList(UniqueList.from(iterable)))

  def unsafeFrom[T](iterable: Iterable[T]): UniqueNonEmptyList[T] =
    from(iterable).getOrElse(throw new IllegalArgumentException("Cannot create UniqueNonEmptyList from empty list"))

  def of[T](t: T, ts: T*): UniqueNonEmptyList[T] = unsafeFrom(t :: ts.toList)

  implicit class ToNonEmptyList[T](val uniqueNonEmptyList: UniqueNonEmptyList[T]) extends AnyVal {
    def toNonEmptyList: NonEmptyList[T] = NonEmptyList.fromListUnsafe(uniqueNonEmptyList.toList)
  }

  implicit class ToSet[T](val uniqueNonEmptyList: UniqueNonEmptyList[T]) extends AnyVal {
    def toSet: CovariantSet[T] = CovariantSet.from(uniqueNonEmptyList.iterator)
  }

}

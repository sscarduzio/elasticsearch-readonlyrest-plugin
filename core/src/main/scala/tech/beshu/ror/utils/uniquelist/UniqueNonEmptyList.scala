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

import cats.implicits._
import cats.Show
import cats.data.NonEmptyList

import scala.collection.SortedSet

class UniqueNonEmptyList[T] private (vector: Vector[T])
  extends BaseUniqueList[T, UniqueNonEmptyList[T]](
    vector,
    newVector => new UniqueNonEmptyList(newVector)
  ) {

  def toUniqueList: UniqueList[T] = UniqueList.fromVector(vector)

  def toNonEmptyList: NonEmptyList[T] = NonEmptyList.fromListUnsafe(vector.toList)
}

object UniqueNonEmptyList {

  def of[T](t: T, ts: T*): UniqueNonEmptyList[T] = unsafeFromList(t :: ts.toList)

  def unsafeFromList[T](list: List[T]): UniqueNonEmptyList[T] =
    fromList(list).getOrElse(throw new IllegalArgumentException("Cannot create UniqueNonEmptyList from empty list"))

  def fromList[T](list: List[T]): Option[UniqueNonEmptyList[T]] = list match {
    case Nil => None
    case nel => Some(new UniqueNonEmptyList[T](nel.toVector.distinct))
  }

  def fromNonEmptyList[T](list: NonEmptyList[T]): UniqueNonEmptyList[T] =
    new UniqueNonEmptyList[T](list.toList.toVector.distinct)

  def unsafeFromSortedSet[T](set: SortedSet[T]): UniqueNonEmptyList[T] =
    fromSortedSet(set).getOrElse(throw new IllegalArgumentException("Cannot create UniqueNonEmptyList from empty set"))

  def fromSortedSet[T](set: SortedSet[T]): Option[UniqueNonEmptyList[T]] =
    if(set.nonEmpty) Some(new UniqueNonEmptyList[T](set.toVector.distinct))
    else None

  def unsafeFromSet[T](set: Set[T]): UniqueNonEmptyList[T] =
    fromSet(set).getOrElse(throw new IllegalArgumentException("Cannot create UniqueNonEmptyList from empty set"))

  def fromSet[T](set: Set[T]): Option[UniqueNonEmptyList[T]] =
    if(set.nonEmpty) Some(new UniqueNonEmptyList[T](set.toVector.distinct))
    else None

  implicit def show[T: Show]: Show[UniqueNonEmptyList[T]] =
    Show.show(_.toList.map(_.show).mkString(","))
}

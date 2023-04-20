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

import cats.Show
import cats.implicits._

import scala.collection.SortedSet

class UniqueList[T] private(vector: Vector[T])
  extends BaseUniqueList[T, UniqueList[T]](
    vector,
    newVector => new UniqueList(newVector)
  ) {

  def mergeWith(other: UniqueList[T]): UniqueList[T] = {
    UniqueList.fromVector(this.vector ++ other.toVector)
  }
}

object UniqueList {
  def fromVector[T](vector: Vector[T]): UniqueList[T] = new UniqueList[T](vector.distinct)
  def empty[T]: UniqueList[T] = fromVector(Vector.empty)
  def of[T](t: T*): UniqueList[T] = fromIterable(t.toList)
  def fromIterable[T](list: Iterable[T]): UniqueList[T] = fromVector(list.toVector)
  def fromSortedSet[T](set: SortedSet[T]): UniqueList[T] = fromVector(set.toVector)

  implicit def show[T: Show]: Show[UniqueList[T]] = Show.show(_.toList.show)
}

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

import scala.collection.{AbstractSet, SortedSet}

private[uniquelist] abstract class BaseUniqueList[T, F <: BaseUniqueList[T, F]](vector: Vector[T],
                                                                                create: Vector[T] => F)
  extends AbstractSet[T]
    with SortedSet[T] {

  override implicit val ordering: Ordering[T] = Ordering.fromLessThan { case (v1, v2) =>
    (vector.indexOf(v1), vector.indexOf(v2)) match {
      case (-1, -1) => v1.hashCode().compareTo(v2.hashCode()) < 0
      case (_, -1) => true
      case (-1, _) => false
      case (idx1, idx2) => idx1.compareTo(idx2) < 0
    }
  }

  override def iterator: Iterator[T] = vector.iterator

  override def rangeImpl(from: Option[T], until: Option[T]): F = {
    (from, until) match {
      case (Some(f), Some(u)) => create(vector.span(_ != f)._2.span(_ != u)._1)
      case (None, Some(u)) => create(vector.span(_ != u)._1)
      case (Some(f), None) => create(vector.span(_ != f)._2)
      case (None, None) => create(vector)
    }
  }

  override def iteratorFrom(start: T): Iterator[T] = vector.iterator.span(_ != start)._2

  override def keysIteratorFrom(start: T): Iterator[T] = iteratorFrom(start)

  override def diff(that: collection.Set[T]): SortedSet[T] = SortedSet(that.toList:_*).diff(this)

  override def contains(elem: T): Boolean = vector.contains(elem)

  override def +(elem: T): F = create(vector :+ elem)

  override def -(elem: T): F = create(vector.filter(_ != elem))

  override def size: Int = vector.size

  override def equals(that: Any): Boolean = that match {
    case ul: BaseUniqueList[T @unchecked, _] => this.toList.equals(ul.toList)
    case _ => false
  }
}

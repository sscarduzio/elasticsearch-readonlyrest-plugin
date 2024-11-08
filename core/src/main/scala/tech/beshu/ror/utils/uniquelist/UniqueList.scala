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

import cats.implicits.*

import scala.collection.{IterableFactory, IterableFactoryDefaults, SeqOps, mutable}

final case class UniqueList[+T] private [uniquelist] (private val underlying: Vector[T])
  extends Iterable[T]
    with IterableFactoryDefaults[T, UniqueList]
    with SeqOps[T, UniqueList, UniqueList[T]] {

  override def iterableFactory: UniqueListFactory = UniqueList

  override def iterator: Iterator[T] = underlying.iterator

  override def apply(i: Int): T = underlying.apply(i)

  override def length: Int = underlying.size

}

object UniqueList extends UniqueListFactory

trait UniqueListFactory extends IterableFactory[UniqueList] {

  private val emptyUniqueListInstance = UniqueList[Any](Vector.empty)

  override def from[T](source: IterableOnce[T]): UniqueList[T] = new UniqueList[T](source.iterator.toVector.distinct)
  override def empty[T]: UniqueList[T] = emptyUniqueListInstance.asInstanceOf[UniqueList[T]]
  override def newBuilder[T]: mutable.Builder[T, UniqueList[T]] = Vector.newBuilder.mapResult(from)
  def of[T](t: T*): UniqueList[T] = from(t)
}
object UniqueListFactory extends UniqueListFactory

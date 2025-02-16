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
package tech.beshu.ror.utils.set

import cats.*
import cats.data.NonEmptyList
import cats.implicits.*
import cats.kernel.Monoid

import scala.collection.{IterableFactory, IterableFactoryDefaults, IterableOnce, mutable}

final case class CovariantSet[+A] private[set](private[set] val underlying: Set[Any])
  extends Iterable[A]
    with IterableFactoryDefaults[A, CovariantSet] {

  override def iterableFactory: CovariantSetFactory = CovariantSet
  override def iterator: Iterator[A] = underlying.iterator.asInstanceOf[Iterator[A]]
}
object CovariantSet extends CovariantSetFactory with CovariantSetExtensions with CovariantSetInstances with CatsInstances

trait CovariantSetFactory extends IterableFactory[CovariantSet] {

  private val emptySetInstance = CovariantSet[Any](Set.empty)

  override def from[A](source: IterableOnce[A]): CovariantSet[A] = new CovariantSet[A](Set.from(source))
  override def empty[A]: CovariantSet[A] = emptySetInstance.asInstanceOf[CovariantSet[A]]
  override def newBuilder[A]: mutable.Builder[A, CovariantSet[A]] =
    Set.newBuilder.mapResult(scalaSet => new CovariantSet[A](scalaSet.asInstanceOf[Set[Any]]))
}
object CovariantSetFactory extends CovariantSetFactory

trait CovariantSetExtensions {

  implicit class ToScalaSet[A](val covariantSet: CovariantSet[A]) {
    def asScala: Set[A] = covariantSet.underlying.asInstanceOf[Set[A]]
  }

  implicit class FromIterable[A](val iterable: IterableOnce[A]) {
    def toCovariantSet: CovariantSet[A] = CovariantSet(iterable.iterator.toSet)
  }

  implicit class FromNonEmptyList[A](val nonEmptyList: NonEmptyList[A]) {
    def toCovariantSet: CovariantSet[A] = nonEmptyList.iterator.toCovariantSet
  }

  implicit class FromArray[A](val array: Array[A]) {
    def toCovariantSet: CovariantSet[A] = CovariantSet(array.toSet)
  }

  implicit class Contains[A](val covariantSet: CovariantSet[A]) {
    def contains(elem: A): Boolean = covariantSet.underlying.contains(elem)
  }

  implicit class Concat[A](val covariantSet: CovariantSet[A]) {
    def concat(that: IterableOnce[A]): CovariantSet[A] =
      CovariantSet(covariantSet.underlying.concat(that))

    @`inline` def ++(that: IterableOnce[A]): CovariantSet[A] = concat(that)

    def +(elem: A): CovariantSet[A] = CovariantSet(covariantSet.underlying + elem)
  }

  implicit class RemovedAll[A](val covariantSet: CovariantSet[A]) {
    def removedAll(that: IterableOnce[A]): CovariantSet[A] =
      CovariantSet(covariantSet.underlying.removedAll(that))

    @`inline` def --(that: IterableOnce[A]): CovariantSet[A] = removedAll(that)
  }

  implicit class Diff[A](val covariantSet: CovariantSet[A]) {
    def diff(other: CovariantSet[A]): CovariantSet[A] =
      CovariantSet(covariantSet.underlying.diff(other.underlying))
  }

  implicit class Intersect[A](val covariantSet: CovariantSet[A]) {
    def intersect(other: CovariantSet[A]): CovariantSet[A] =
      CovariantSet(covariantSet.underlying.intersect(other.underlying))
  }

  implicit class SubsetOf[A](val covariantSet: CovariantSet[A]) {
    def subsetOf(other: CovariantSet[A]): Boolean =
      covariantSet.underlying.subsetOf(other.underlying)
  }


}

trait CovariantSetInstances {

  implicit def monoid[A]: Monoid[CovariantSet[A]] = Monoid.instance[CovariantSet[A]](
    emptyValue = CovariantSetFactory.empty,
    cmb = (a, b) => a ++ b
  )
}

trait CatsInstances {

  implicit val covariantSetTraverse: Traverse[CovariantSet] = new Traverse[CovariantSet] {
    override def traverse[G[_] : Applicative, A, B](fa: CovariantSet[A])(f: A => G[B]): G[CovariantSet[B]] = {
      val gset: G[Set[Any]] = fa.underlying.asInstanceOf[Set[A]].toList.traverse(f).map(_.toSet)
      gset.map(new CovariantSet[B](_))
    }

    override def foldLeft[A, B](fa: CovariantSet[A], b: B)(f: (B, A) => B): B =
      fa.underlying.foldLeft(b)(f.asInstanceOf[(B, Any) => B])

    override def foldRight[A, B](fa: CovariantSet[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] =
      fa.underlying.foldRight(lb)(f.asInstanceOf[(Any, Eval[B]) => Eval[B]])
  }
}
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

  extension [A](covariantSet: CovariantSet[A]) {
    def asScala: Set[A] = covariantSet.underlying.asInstanceOf[Set[A]]
    def contains(elem: A): Boolean = covariantSet.underlying.contains(elem)
    def concat(that: IterableOnce[A]): CovariantSet[A] = CovariantSet(covariantSet.underlying.concat(that))
    inline def ++(that: IterableOnce[A]): CovariantSet[A] = concat(that)
    def +(elem: A): CovariantSet[A] = CovariantSet(covariantSet.underlying + elem)
    def removedAll(that: IterableOnce[A]): CovariantSet[A] = CovariantSet(covariantSet.underlying.removedAll(that))
    inline def --(that: IterableOnce[A]): CovariantSet[A] = removedAll(that)
    def diff(other: CovariantSet[A]): CovariantSet[A] = CovariantSet(covariantSet.underlying.diff(other.underlying))
    def intersect(other: CovariantSet[A]): CovariantSet[A] = CovariantSet(covariantSet.underlying.intersect(other.underlying))
    def subsetOf(other: CovariantSet[A]): Boolean = covariantSet.underlying.subsetOf(other.underlying)
  }

  extension [A](iterable: IterableOnce[A]) {
    def toCovariantSet: CovariantSet[A] = iterable match {
      case s: Set[A @unchecked] => new CovariantSet[A](s.asInstanceOf[Set[Any]])
      case other => CovariantSet(other.iterator.toSet)
    }
  }

  extension [A](nonEmptyList: NonEmptyList[A]) {
    def toCovariantSet: CovariantSet[A] = nonEmptyList.iterator.toCovariantSet
  }

  extension [A](array: Array[A]) {
    def toCovariantSet: CovariantSet[A] = CovariantSet(array.toSet)
  }
}

trait CovariantSetInstances {

  implicit def monoid[A]: Monoid[CovariantSet[A]] =
    cachedMonoid.asInstanceOf[Monoid[CovariantSet[A]]]

  private val cachedMonoid: Monoid[CovariantSet[Any]] = Monoid.instance[CovariantSet[Any]](
    emptyValue = CovariantSetFactory.empty,
    cmb = (a, b) => a ++ b
  )
}

trait CatsInstances {

  implicit val covariantSetTraverse: Traverse[CovariantSet] = new Traverse[CovariantSet] {
    override def traverse[G[_] : Applicative, A, B](fa: CovariantSet[A])(f: A => G[B]): G[CovariantSet[B]] = {
      val G = Applicative[G]
      val gset = fa.underlying.foldLeft(G.pure(Set.newBuilder[Any])) { (acc, a) =>
        G.map2(acc, f(a.asInstanceOf[A]))((builder, b) => builder += b)
      }
      G.map(gset)(builder => new CovariantSet[B](builder.result()))
    }

    override def foldLeft[A, B](fa: CovariantSet[A], b: B)(f: (B, A) => B): B =
      fa.underlying.foldLeft(b)(f.asInstanceOf[(B, Any) => B])

    override def foldRight[A, B](fa: CovariantSet[A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] =
      fa.underlying.foldRight(lb)(f.asInstanceOf[(Any, Eval[B]) => Eval[B]])
  }
}
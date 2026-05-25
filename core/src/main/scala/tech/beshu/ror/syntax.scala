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
package tech.beshu.ror

import tech.beshu.ror.utils.set.{CovariantSet, CovariantSetConversions, CovariantSetInstances}

import scala.collection.mutable

object syntax
  extends CovariantSetConversions
  with CovariantSetInstances {

  type Set[A] = CovariantSet[A]
  object Set {
    def empty[A]: CovariantSet[A] = CovariantSet.empty
    def apply[A](elems: A*): CovariantSet[A] = CovariantSet.from(elems)
    def newBuilder[A]: mutable.Builder[A, CovariantSet[A]] = CovariantSet.newBuilder[A]

    /** Build a Set using a pre-sized builder. Use when result size is known up front. */
    def sized[A](sizeHint: Int)(build: mutable.Builder[A, CovariantSet[A]] => Unit): CovariantSet[A] = {
      val b = newBuilder[A](sizeHint)
      build(b)
      b.result()
    }

    /** Map a sized source into a Set. Pre-sizes the result builder to source.size. */
    def mapFrom[A, B](source: Iterable[A])(f: A => B): CovariantSet[B] = {
      val b = newBuilder[B](source.size)
      source.foreach(a => b += f(a))
      b.result()
    }

    /** Builder pre-sized for the expected element count — avoids HashSetBuilder resize chains. */
    private def newBuilder[A](sizeHint: Int): mutable.Builder[A, CovariantSet[A]] = {
      val b = CovariantSet.newBuilder[A]
      b.sizeHint(sizeHint)
      b
    }
  }

  implicit class MutableHashMapBuilderOps[K, V, C](private val m: mutable.HashMap[K, mutable.Builder[V, C]]) extends AnyVal {
    def drainToMap: scala.collection.immutable.Map[K, C] = {
      val b = scala.collection.immutable.Map.newBuilder[K, C]
      b.sizeHint(m.size)
      m.foreach { case (k, v) => b += (k -> v.result()) }
      b.result()
    }
  }
}

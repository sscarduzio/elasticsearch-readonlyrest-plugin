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
package tech.beshu.ror.utils

import scala.collection.immutable.ListMap

final case class NonEmptyListMap[K, +V] private(underlying: ListMap[K, V]):
  def head: (K, V) = underlying.head

  def tail: ListMap[K, V] = underlying.tail

  def keys: Iterable[K] = underlying.keys

  def values: Iterable[V] = underlying.values

  def get(key: K): Option[V] = underlying.get(key)

  def updated[V1 >: V](k: K, v: V1): NonEmptyListMap[K, V1] =
    NonEmptyListMap.fromUnsafe(underlying.updated(k, v))

  def apply(key: K): V = underlying.apply(key)

object NonEmptyListMap:

  def from[K, V](m: ListMap[K, V]): Option[NonEmptyListMap[K, V]] =
    if m.nonEmpty then Some(new NonEmptyListMap(m))
    else None

  def one[K, V](k: K, v: V): NonEmptyListMap[K, V] =
    new NonEmptyListMap(ListMap(k -> v))

  private def fromUnsafe[K, V](m: ListMap[K, V]) =
    new NonEmptyListMap(m)

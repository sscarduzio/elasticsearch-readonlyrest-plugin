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
package tech.beshu.ror.es.utils

import org.elasticsearch.common.collect.ImmutableOpenMap

import scala.jdk.CollectionConverters._

object EsCollectionsScalaUtils {

  implicit class ImmutableOpenMapOps[K, V](val value: ImmutableOpenMap[K, V]) extends AnyVal {

    def asSafeKeys: Set[K] = Option(value).map(_.keysIt().asScala.toSet).getOrElse(Set.empty)

    def asSafeValues: Set[V] = Option(value).map(_.valuesIt().asScala.toSet).getOrElse(Set.empty)

    def asSafeEntriesList: List[(K, V)] =
      Option(value) match {
        case Some(map) =>
          map
            .keysIt().asScala
            .map { key =>
              (key, map.get(key))
            }
            .toList
        case None =>
          List.empty
      }
  }

  object ImmutableOpenMapOps {
    def from[K, V](map: Map[K, V]): ImmutableOpenMap[K, V] = {
      new ImmutableOpenMap.Builder[K, V]()
        .putAll(map.asJava)
        .build()
    }

    def empty[K, V]: ImmutableOpenMap[K, V] =
      from(Map.empty)
  }
}

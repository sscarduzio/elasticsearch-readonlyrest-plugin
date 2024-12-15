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
package tech.beshu.ror.accesscontrol.utils

import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}

import scala.language.implicitConversions

class RequestedIndicesOps(val indices: Iterable[RequestedIndex[ClusterIndexName]]) extends AnyVal {

  def randomNonexistentIndex(): RequestedIndex[ClusterIndexName] = {
    val foundIndex = indices.find(_.name.hasWildcard) orElse indices.headOption
    foundIndex match {
      case Some(indexName) => indexName.randomNonexistentIndex()
      case None => RequestedIndex(ClusterIndexName.Local.randomNonexistentIndex(), excluded = false)
    }
  }

}

object RequestedIndicesOps {
  implicit def toOps(indices: Iterable[RequestedIndex[ClusterIndexName]]): RequestedIndicesOps =
    new RequestedIndicesOps(indices)
}

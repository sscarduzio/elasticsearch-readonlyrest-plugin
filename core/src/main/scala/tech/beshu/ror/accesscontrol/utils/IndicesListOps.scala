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

import tech.beshu.ror.accesscontrol.domain.IndexName

import scala.language.implicitConversions

class IndicesListOps(val indices: List[IndexName]) extends AnyVal {

  def randomNonexistentIndex(): IndexName = {
    val foundIndex = indices.find(_.hasWildcard) orElse indices.headOption
    foundIndex match {
//      case Some(indexName) if indexName.isClusterIndex => IndexName.randomNonexistentIndex(
        // todo:
//          indexName.stringify.replace(":", "_") // we don't want to call remote cluster
//      )
      case Some(indexName) => indexName.randomNonexistentIndex()
      case None => IndexName.Local.randomNonexistentIndex()
    }
  }

}

object IndicesListOps {
  implicit def toOps(indices: List[IndexName]): IndicesListOps = new IndicesListOps(indices)
}

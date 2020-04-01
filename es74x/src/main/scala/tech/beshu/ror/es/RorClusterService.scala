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
package tech.beshu.ror.es

import tech.beshu.ror.accesscontrol.domain
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.es.RorClusterService._

trait RorClusterService {

  // todo: remove unecessary
  def indexOrAliasUuids(indexOrAlias: IndexOrAlias): Set[IndexUuid]
  def allIndices: Set[IndexName]
  def allIndicesAndAliases: Map[IndexName, Set[AliasName]]
  def findTemplatesOfIndices(indices: Set[IndexName]): Set[IndexName]
  def getTemplatesWithPatterns: Map[TemplateName, Set[IndexPatten]]
  def expandIndices(indices: Set[domain.IndexName]): Set[domain.IndexName]
}

object RorClusterService {
  type IndexOrAlias = IndexName
  type AliasName = IndexName
  type IndexUuid = String
  type IndexPatten = String
  type TemplateName = String
}
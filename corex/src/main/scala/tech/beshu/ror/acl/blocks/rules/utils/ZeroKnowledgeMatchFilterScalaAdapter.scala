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
package tech.beshu.ror.acl.blocks.rules.utils

import tech.beshu.ror.acl.aDomain.IndexName
import tech.beshu.ror.ZeroKnowledgeMatchFilter
import tech.beshu.ror.acl.blocks.rules.utils.ZeroKnowledgeMatchFilterScalaAdapter.AlterResult

import scala.collection.JavaConverters._

class ZeroKnowledgeMatchFilterScalaAdapter {

  def alterIndicesIfNecessary(indices: Set[IndexName], matcher: Matcher): AlterResult = {
    Option(ZeroKnowledgeMatchFilter.alterIndicesIfNecessary(
      indices.map(_.value).asJava,
      matcher.underlying
    )) match {
      case Some(alteredIndices) => AlterResult.Altered(alteredIndices.asScala.map(IndexName.apply).toSet)
      case None => AlterResult.NotAltered
    }
  }
}

object ZeroKnowledgeMatchFilterScalaAdapter {
  sealed trait AlterResult
  object AlterResult {
    case object NotAltered extends AlterResult
    final case class Altered(indices: Set[IndexName]) extends AlterResult
  }
}

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

import tech.beshu.ror.acl.domain.IndexName
import tech.beshu.ror.ZeroKnowledgeIndexFilter
import tech.beshu.ror.acl.blocks.rules.utils.ZeroKnowledgeIndexFilterScalaAdapter.CheckResult

import scala.collection.JavaConverters._

class ZeroKnowledgeIndexFilterScalaAdapter(underlying: ZeroKnowledgeIndexFilter) {

  def check(indices: Set[IndexName], matcher: Matcher): CheckResult = {
    val processedIndices: java.util.Set[String] = scala.collection.mutable.Set.empty[String].asJava
    val result = underlying.alterIndicesIfNecessaryAndCheck(
      indices.map(_.value).asJava,
      matcher.underlying,
      processedIndices.addAll _
    )
    if(result) CheckResult.Ok(processedIndices.asScala.map(IndexName.apply).toSet)
    else CheckResult.Failed
  }
}

object ZeroKnowledgeIndexFilterScalaAdapter {
  sealed trait CheckResult
  object CheckResult {
    final case class Ok(processedIndices: Set[IndexName]) extends CheckResult
    case object Failed extends CheckResult
  }
}

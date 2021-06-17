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
package tech.beshu.ror.accesscontrol.matchers

import tech.beshu.ror.accesscontrol.domain.ClusterIndexName
import tech.beshu.ror.accesscontrol.matchers.ZeroKnowledgeRemoteIndexFilterScalaAdapter.CheckResult
import tech.beshu.ror.utils.ZeroKnowledgeIndexFilter

import scala.collection.JavaConverters._

class ZeroKnowledgeRemoteIndexFilterScalaAdapter {

  private val underlying = new ZeroKnowledgeIndexFilter(true)

  def check(indices: Set[ClusterIndexName.Remote], matcher: Matcher[ClusterIndexName.Remote]): CheckResult = {
    val processedIndices: java.util.Set[String] = scala.collection.mutable.Set.empty[String].asJava
    val result = underlying.alterIndicesIfNecessaryAndCheck(
      indices.map(_.stringify).asJava,
      Matcher.asMatcherWithWildcards(matcher),
      processedIndices.addAll _
    )
    if(result) CheckResult.Ok(processedIndices.asScala.flatMap(ClusterIndexName.Remote.fromString).toSet)
    else CheckResult.Failed
  }
}

object ZeroKnowledgeRemoteIndexFilterScalaAdapter {
  sealed trait CheckResult
  object CheckResult {
    final case class Ok(processedIndices: Set[ClusterIndexName.Remote]) extends CheckResult
    case object Failed extends CheckResult
  }
}

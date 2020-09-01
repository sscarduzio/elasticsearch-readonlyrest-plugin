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
package tech.beshu.ror.accesscontrol.blocks.rules.utils

import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.blocks.rules.utils.ZeroKnowledgeRepositoryFilterScalaAdapter.CheckResult
import tech.beshu.ror.accesscontrol.domain.RepositoryName
import tech.beshu.ror.utils.ZeroKnowledgeIndexFilter

import scala.collection.JavaConverters._

class ZeroKnowledgeRepositoryFilterScalaAdapter(underlying: ZeroKnowledgeIndexFilter) {

  def check(indices: Set[RepositoryName], matcher: Matcher): CheckResult = {
    val processedRepositories: java.util.Set[String] = scala.collection.mutable.Set.empty[String].asJava
    val result = underlying.alterIndicesIfNecessaryAndCheck(
      indices.map(_.value.value).asJava,
      matcher.underlying,
      processedRepositories.addAll _
    )
    if(result) CheckResult.Ok(processedRepositories.asScala.map(str => RepositoryName(NonEmptyString.unsafeFrom(str))).toSet)
    else CheckResult.Failed
  }
}

object ZeroKnowledgeRepositoryFilterScalaAdapter {
  sealed trait CheckResult
  object CheckResult {
    final case class Ok(processedRepositories: Set[RepositoryName]) extends CheckResult
    case object Failed extends CheckResult
  }
}
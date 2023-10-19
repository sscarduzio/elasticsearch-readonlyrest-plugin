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

import tech.beshu.ror.accesscontrol.domain.RepositoryName
import tech.beshu.ror.accesscontrol.matchers.ZeroKnowledgeRepositoryFilterScalaAdapter.CheckResult
import tech.beshu.ror.utils.{StringPatternsMatcherJava, ZeroKnowledgeIndexFilter}

import scala.jdk.CollectionConverters._

class ZeroKnowledgeRepositoryFilterScalaAdapter(underlying: ZeroKnowledgeIndexFilter) {

  def check(repositories: Set[RepositoryName], matcher: PatternsMatcher[RepositoryName]): CheckResult = {
    val processedRepositories: java.util.Set[String] = scala.collection.mutable.Set.empty[String].asJava
    val result = underlying.alterIndicesIfNecessaryAndCheck(
      repositories
        .collect {
          case RepositoryName.Pattern(v) => v.value
          case RepositoryName.Full(v) => v.value
        }
        .asJava,
      new StringPatternsMatcherJava(matcher),
      processedRepositories.addAll _
    )
    if(result) CheckResult.Ok(processedRepositories.asScala.flatMap(RepositoryName.from).toSet)
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
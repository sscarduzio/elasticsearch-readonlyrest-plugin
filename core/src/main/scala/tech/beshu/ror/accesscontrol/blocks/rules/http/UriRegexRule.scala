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
package tech.beshu.ror.accesscontrol.blocks.rules.http

import cats.data.{NonEmptyList, NonEmptySet}
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.{RegularRule, RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.http.UriRegexRule.Settings
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.request.RequestContext

import java.util.regex.Pattern

class UriRegexRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = UriRegexRule.Name.name

  override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    RuleResult.resultBasedOnCondition(blockContext) {
      settings
        .uriPatterns
        .exists(variableMatchingRequestedUri(blockContext))
    }
  }

  private def variableMatchingRequestedUri[B <: BlockContext : BlockContextUpdater](blockContext: B)
                                                                                   (patternVariable: RuntimeMultiResolvableVariable[Pattern]): Boolean =
    patternVariable
      .resolve(blockContext)
      .exists(matchingResolvedPattern(blockContext.requestContext))

  private def matchingResolvedPattern(requestContext: RequestContext)
                                     (patterns: NonEmptyList[Pattern]): Boolean =
    patterns
      .exists {
        _.matcher(requestContext.uriPath.value.value).find()
      }
}

object UriRegexRule {

  implicit case object Name extends RuleName[UriRegexRule] {
    override val name = Rule.Name("uri_re")
  }

  final case class Settings(uriPatterns: NonEmptySet[RuntimeMultiResolvableVariable[Pattern]])

}

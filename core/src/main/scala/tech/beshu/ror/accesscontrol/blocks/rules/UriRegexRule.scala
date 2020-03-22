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
package tech.beshu.ror.accesscontrol.blocks.rules

import java.util.regex.Pattern

import cats.data.{NonEmptyList, NonEmptySet}
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.UriRegexRule.Settings
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.domain.Operation
import tech.beshu.ror.accesscontrol.request.RequestContext

class UriRegexRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = UriRegexRule.name

  override def check[B <: BlockContext[B]](blockContext: B): Task[RuleResult[B]] = Task {
    RuleResult.fromCondition(blockContext) {
      settings
        .uriPatterns
        .exists(variableMatchingRequestedUri(blockContext))
    }
  }

  private def variableMatchingRequestedUri[B <: BlockContext[B]](blockContext: B)
                                                                (patternVariable: RuntimeMultiResolvableVariable[Pattern]): Boolean =
    patternVariable
      .resolve(blockContext)
      .exists(matchingResolvedPattern(blockContext.requestContext))

  private def matchingResolvedPattern[O <: Operation](requestContext: RequestContext[O])
                                                     (patterns: NonEmptyList[Pattern]): Boolean =
    patterns
      .exists {
        _.matcher(requestContext.uriPath.value).find()
      }
}

object UriRegexRule {
  val name = Rule.Name("uri_re")

  final case class Settings(uriPatterns: NonEmptySet[RuntimeMultiResolvableVariable[Pattern]])

}

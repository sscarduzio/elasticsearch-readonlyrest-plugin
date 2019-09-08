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

import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.UriRegexRule.Settings
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable
import tech.beshu.ror.accesscontrol.request.RequestContext

class UriRegexRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = UriRegexRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task {
    RuleResult.fromCondition(blockContext) {
      settings
        .uriPatterns.head
        .resolve(requestContext, blockContext)
        .exists {
          _.matcher(requestContext.uriPath.value).find()
        }
    }
  }
}

object UriRegexRule {
  val name = Rule.Name("uri_re")

  final case class Settings(uriPatterns: NonEmptySet[RuntimeSingleResolvableVariable[Pattern]])

}

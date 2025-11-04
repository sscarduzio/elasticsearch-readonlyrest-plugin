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

import monix.eval.Task
import squants.information.Information
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.http.MaxBodyLengthRule.Settings
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}

class MaxBodyLengthRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = MaxBodyLengthRule.Name.name

  override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    RuleResult.resultBasedOnCondition(blockContext) {
      blockContext.requestContext.restRequest.contentLength <= settings.maxContentLength
    }
  }
}

object MaxBodyLengthRule {

  implicit case object Name extends RuleName[MaxBodyLengthRule] {
    override val name = Rule.Name("max_body_length")
  }

  final case class Settings(maxContentLength: Information)
}
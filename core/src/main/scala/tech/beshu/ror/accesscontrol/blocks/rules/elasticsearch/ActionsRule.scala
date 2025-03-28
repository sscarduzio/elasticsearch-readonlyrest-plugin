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
package tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch

import cats.data.NonEmptySet
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.ActionsRule.Settings
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.{Action, RequestId}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.implicits.*

class ActionsRule(val settings: Settings)
  extends RegularRule with Logging {

  override val name: Rule.Name = ActionsRule.Name.name

  private val matcher: PatternsMatcher[Action] = PatternsMatcher.create(settings.actions.toSortedSet)

  override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    val requestContext = blockContext.requestContext
    if (matcher.`match`(requestContext.action)) {
      RuleResult.Fulfilled(blockContext)
    } else {
      implicit val requestId: RequestId = blockContext.requestContext.id.toRequestId
      logger.debug(s"[${requestId.show}] This request uses the action '${requestContext.action.show}' and none of them is on the list.")
      RuleResult.Rejected()
    }
  }
}

object ActionsRule {

  implicit case object Name extends RuleName[ActionsRule] {
    override val name = Rule.Name("actions")
  }

  final case class Settings(actions: NonEmptySet[Action])

}

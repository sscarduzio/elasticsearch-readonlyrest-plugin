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

import cats.data.NonEmptySet
import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.rules.ActionsRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.{RegularRule, RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.matchers.MatcherWithWildcardsScalaAdapter
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.Action
import tech.beshu.ror.accesscontrol.show.logs._

class ActionsRule(val settings: Settings)
  extends RegularRule with Logging {

  override val name: Rule.Name = ActionsRule.Name.name

  private val matcher: MatcherWithWildcardsScalaAdapter[Action] = MatcherWithWildcardsScalaAdapter[Action](settings.actions.toSortedSet)

  override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    val requestContext = blockContext.requestContext
    if (matcher.`match`(requestContext.action)) {
      RuleResult.Fulfilled(blockContext)
    } else {
      logger.debug(s"This request uses the action '${requestContext.action.show}' and none of them is on the list.")
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

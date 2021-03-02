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
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.UsersRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.utils.MatcherWithWildcardsScalaAdapter
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.{LoggedUser, User}
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll

class UsersRule(val settings: Settings,
                implicit val caseMappingEquality: UserIdCaseMappingEquality)
  extends RegularRule {

  override val name: Rule.Name = UsersRule.name

  override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    blockContext.userMetadata.loggedUser match {
      case None => Rejected()
      case Some(user) => matchUser(user, blockContext)
    }
  }

  private def matchUser[B <: BlockContext](user: LoggedUser, blockContext: B): RuleResult[B] = {
    val resolvedIds = resolveAll(settings.userIds.toNonEmptyList, blockContext).toSet
    RuleResult.resultBasedOnCondition(blockContext) {
      MatcherWithWildcardsScalaAdapter[User.Id](resolvedIds).`match`(user.id)
    }
  }
}

object UsersRule {
  val name = Rule.Name("users")

  final case class Settings(userIds: NonEmptySet[RuntimeMultiResolvableVariable[User.Id]])
}


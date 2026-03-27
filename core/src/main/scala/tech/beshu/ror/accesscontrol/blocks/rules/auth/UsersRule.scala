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
package tech.beshu.ror.accesscontrol.blocks.rules.auth

import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleName}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.UsersRule.Settings
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, Decision}
import tech.beshu.ror.accesscontrol.domain.{CaseSensitivity, LoggedUser, User}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll

class UsersRule(val settings: Settings,
                implicit val userIdCaseSensitivity: CaseSensitivity)
  extends RegularRule {

  override val name: Rule.Name = UsersRule.Name.name

  override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] = Task {
    blockContext.blockMetadata.loggedUser match {
      case Some(user) => matchUser(user, blockContext)
      case None => Denied(Cause.NotAuthorized)
    }
  }

  private def matchUser[B <: BlockContext](user: LoggedUser, blockContext: B): Decision[B] = {
    val resolvedIds = resolveAll(settings.userIds.toNonEmptyList, blockContext).toSet
    Decision.permit(`with` = blockContext)(
      when = PatternsMatcher.create(resolvedIds).`match`(user.id)
    )
  }
}

object UsersRule {

  implicit case object Name extends RuleName[UsersRule] {
    override val name = Rule.Name("users")
  }

  final case class Settings(userIds: NonEmptySet[RuntimeMultiResolvableVariable[User.Id]])
}


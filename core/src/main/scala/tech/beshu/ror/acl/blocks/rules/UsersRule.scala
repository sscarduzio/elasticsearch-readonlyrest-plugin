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
package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.Rejected
import tech.beshu.ror.acl.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.acl.blocks.rules.UsersRule.Settings
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeSingleResolvableVariable
import tech.beshu.ror.acl.domain.{LoggedUser, User}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.utils.MatcherWithWildcards

import scala.collection.JavaConverters._

class UsersRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = UsersRule.name

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task {
    blockContext.loggedUser match {
      case None => Rejected
      case Some(user) => matchUser(user, requestContext, blockContext)
    }
  }

  private def matchUser(user: LoggedUser, requestContext: RequestContext, blockContext: BlockContext): RuleResult = {
    val resolvedIds = settings
      .userIds
      .toNonEmptyList
      .toList
      .flatMap(_.resolve(requestContext, blockContext).toOption)
      .toSet
    RuleResult.fromCondition(blockContext) {
      new MatcherWithWildcards(resolvedIds.map(_.value).asJava).`match`(user.id.value)
    }
  }
}

object UsersRule {
  val name = Rule.Name("users")

  final case class Settings(userIds: NonEmptySet[RuntimeSingleResolvableVariable[User.Id]])
}


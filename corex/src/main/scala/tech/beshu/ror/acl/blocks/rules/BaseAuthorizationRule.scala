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
import tech.beshu.ror.acl.blocks.rules.BaseAuthorizationRule.AuthorizationResult
import tech.beshu.ror.acl.blocks.rules.Rule.AuthorizationRule
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.domain.{Group, LoggedUser}
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.utils.ScalaOps._

abstract class BaseAuthorizationRule
  extends AuthorizationRule {

  protected def authorize(requestContext: RequestContext,
                          blockContext: BlockContext,
                          user: LoggedUser): Task[AuthorizationResult]

  override def check(requestContext: RequestContext, blockContext: BlockContext): Task[Rule.RuleResult] = {
    blockContext.loggedUser match {
      case Some(user) =>
        authorize(requestContext, blockContext, user)
          .map {
            case AuthorizationResult.Unauthorized =>
              Rejected
            case AuthorizationResult.Authorized(currentGroup, availableGroups) =>
              Fulfilled {
                blockContext
                  .withCurrentGroup(currentGroup)
                  .withAddedAvailableGroups(availableGroups)
              }
          }
      case None =>
        Task.now(Rejected)
    }
  }

  protected def pickCurrentGroupFrom(resolvedGroups: NonEmptySet[Group]): Group = {
    resolvedGroups.toSortedSet.toList.minBy(_.value)
  }
}

object BaseAuthorizationRule {
  trait AuthorizationResult
  object AuthorizationResult {
    case object Unauthorized extends AuthorizationResult
    final case class Authorized(currentGroup: Group, availableGroups: NonEmptySet[Group]) extends AuthorizationResult
  }
}

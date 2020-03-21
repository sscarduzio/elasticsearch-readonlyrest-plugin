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
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.BaseAuthorizationRule.AuthorizationResult
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthorizationRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.domain.{Group, LoggedUser, Operation}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

abstract class BaseAuthorizationRule
  extends AuthorizationRule {

  protected def authorize[T <: Operation](requestContext: RequestContext[T],
                                          blockContext: BlockContext[T],
                                          user: LoggedUser): Task[AuthorizationResult]

  override def check[T <: Operation](requestContext: RequestContext[T],
                                     blockContext: BlockContext[T]): Task[Rule.RuleResult[T]] = {
    blockContext.loggedUser match {
      case Some(user) =>
        authorize(requestContext, blockContext, user)
          .map {
            case AuthorizationResult.Unauthorized =>
              Rejected()
            case AuthorizationResult.Authorized(availableGroups) =>
              Fulfilled(blockContext.withAddedAvailableGroups(availableGroups))
          }
      case None =>
        Task.now(Rejected())
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
    final case class Authorized(availableGroups: UniqueNonEmptyList[Group]) extends AuthorizationResult
  }
}

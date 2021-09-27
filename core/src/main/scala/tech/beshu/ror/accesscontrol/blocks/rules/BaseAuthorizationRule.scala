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

import cats.implicits._
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthorizationImpersonationSupport.Groups
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthorizationRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.Group._
import tech.beshu.ror.accesscontrol.domain.{Group, LoggedUser}
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

abstract class BaseAuthorizationRule
  extends AuthorizationRule {

  protected def groupsPermittedByRule: UniqueNonEmptyList[Group]

  protected def groupsPermittedByAllRulesOfThisType: UniqueNonEmptyList[Group]

  protected def userGroups[B <: BlockContext](blockContext: B, user: LoggedUser): Task[UniqueList[Group]]

  protected def loggedUserPreconditionCheck(user: LoggedUser): Either[Unit, Unit] = Right(())

  override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = {
    blockContext.userMetadata.loggedUser match {
      case Some(user@LoggedUser.DirectlyLoggedUser(_)) =>
        loggedUserPreconditionCheck(user) match {
          case Left(_) => Task.now(Rejected())
          case Right(_) => authorizeDirectlyLoggedUser(blockContext, user)
        }
      case Some(user@LoggedUser.ImpersonatedUser(_, _)) =>
        loggedUserPreconditionCheck(user) match {
          case Left(_) => Task.now(Rejected())
          case Right(_) => authorizeImpersonatedUser(blockContext, user)
        }
      case None =>
        Task.now(Rejected())
    }
  }

  private def authorizeImpersonatedUser[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                                 user: LoggedUser.ImpersonatedUser): Task[RuleResult[B]] = {
    mockedGroupsOf(user.id) match {
      case Groups.Present(mockedGroups) =>
        Task.delay(canBeAuthorized(blockContext, groupsPermittedByRule, mockedGroups))
      case Groups.CannotCheck =>
        Task.now(Rejected(Cause.ImpersonationNotSupported))
    }
  }

  private def authorizeDirectlyLoggedUser[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                                   user: LoggedUser.DirectlyLoggedUser): Task[RuleResult[B]] = {
    val allAllowedGroups = groupsPermittedByRule
    blockContext.userMetadata.currentGroup match {
      case Some(currentGroup) if allAllowedGroups.exists(_ === currentGroup) =>
        userGroups(blockContext, user)
          .map(canBeAuthorized(blockContext, allAllowedGroups, _))
      case Some(_) =>
        Task.now(Rejected())
      case None =>
        userGroups(blockContext, user)
          .map(canBeAuthorized(blockContext, allAllowedGroups, _))
    }
  }

  private def canBeAuthorized[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                       permittedGroups: UniqueNonEmptyList[Group],
                                                                       userGroups: UniqueList[Group]): RuleResult[B] = {
    UniqueNonEmptyList.fromList(groupsPermittedByAllRulesOfThisType.toList.intersect(userGroups.toList)) match {
      case Some(allowedGroups) =>
        Fulfilled(blockContext.withUserMetadata(_.addAvailableGroups(allowedGroups)))
      case None =>
        Rejected()
    }
  }
}

object BaseAuthorizationRule {
  trait AuthorizationResult
  object AuthorizationResult {
    case object Unauthorized extends AuthorizationResult
    final case class Authorized(availableGroups: UniqueNonEmptyList[Group]) extends AuthorizationResult
  }
}

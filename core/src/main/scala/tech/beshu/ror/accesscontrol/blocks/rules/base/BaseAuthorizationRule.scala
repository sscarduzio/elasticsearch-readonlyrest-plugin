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
package tech.beshu.ror.accesscontrol.blocks.rules.base

import cats.Eq
import monix.eval.Task
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult.Rejected.Cause.ImpersonationNotSupported
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.{AuthorizationRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.SimpleAuthorizationImpersonationSupport.Groups
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.{Impersonation, ImpersonationSettings, SimpleAuthorizationImpersonationSupport}
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.{DirectlyLoggedUser, ImpersonatedUser}
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.{Group, LoggedUser, User}
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

trait BaseAuthorizationRule extends AuthorizationRule with SimpleAuthorizationImpersonationSupport {
  protected def calculateAllowedGroupsForUser(usersGroups: UniqueNonEmptyList[Group]): Option[UniqueNonEmptyList[Group]]
  protected def caseMappingEquality: UserIdCaseMappingEquality

  protected def groupsPermittedByRule: UniqueNonEmptyList[Group]

  protected def groupsPermittedByAllRulesOfThisType: UniqueNonEmptyList[Group]

  protected def userGroups[B <: BlockContext](blockContext: B, user: LoggedUser): Task[UniqueList[Group]]

  protected def loggedUserPreconditionCheck(user: LoggedUser): Either[Unit, Unit] = Right(())

  override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = {
    authorize(blockContext)
  }

  override protected[base] def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = {
    (blockContext.userMetadata.loggedUser, impersonation) match {
      case (Some(user@ImpersonatedUser(_, _)), Impersonation.Enabled(ImpersonationSettings(_, mocksProvider))) =>
        loggedUserPreconditionCheck(user) match {
          case Left(_) => Task.now(Rejected())
          case Right(_) => authorizeImpersonatedUser(blockContext, user, mocksProvider)
        }
      case (Some(ImpersonatedUser(_, _)), Impersonation.Disabled) =>
        Task.now(Rejected(ImpersonationNotSupported))
      case (Some(user@DirectlyLoggedUser(_)), _) =>
        loggedUserPreconditionCheck(user) match {
          case Left(_) => Task.now(Rejected())
          case Right(_) => authorizeLoggedUser(blockContext, user)
        }
      case (None, _) =>
        Task.now(Rejected())
    }
  }

  private def authorizeImpersonatedUser[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                                 user: LoggedUser.ImpersonatedUser,
                                                                                 mocksProvider: MocksProvider): Task[RuleResult[B]] = {
    implicit val requestId: RequestId = blockContext.requestContext.id.toRequestId
    implicit val eqUserId: Eq[User.Id] = caseMappingEquality.toOrder
    mockedGroupsOf(user.id, mocksProvider) match {
      case Groups.Present(mockedGroups) =>
        authorizeLoggedUser(
          blockContext,
          user,
          userGroupsProvider = (_, _) => Task.now(mockedGroups)
        )
      case Groups.CannotCheck =>
        Task.now(Rejected(Cause.ImpersonationNotSupported))
    }
  }

  private def authorizeLoggedUser[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                           user: LoggedUser): Task[RuleResult[B]] = {
    authorizeLoggedUser(
      blockContext,
      user,
      userGroupsProvider = userGroups
    )
  }

  private def authorizeLoggedUser[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                           user: LoggedUser,
                                                                           userGroupsProvider: (B, LoggedUser) => Task[UniqueList[Group]]): Task[RuleResult[B]] = {
    blockContext.userMetadata.currentGroup match {
      case Some(group) if !groupsPermittedByRule.contains(group) =>
        Task.now(RuleResult.Rejected())
      case Some(_) | None =>
        userGroupsProvider(blockContext, user)
          .map(uniqueList => UniqueNonEmptyList.fromSet(uniqueList.toSet))
          .map {
            case Some(fetchedUserGroups) =>
              calculateAllowedGroupsForUser(fetchedUserGroups) match {
                case Some(_) =>
                  Fulfilled(blockContext.withUserMetadata(
                    _.addAvailableGroups(allGroupsIntersection(fetchedUserGroups))
                  ))
                case None =>
                  RuleResult.Rejected()
              }
            case None =>
              RuleResult.Rejected()
          }
    }
  }

  private def allGroupsIntersection(availableGroups: UniqueNonEmptyList[Group]) = {
    UniqueNonEmptyList.unsafeFromSortedSet(groupsPermittedByAllRulesOfThisType.intersect(availableGroups)) // it is safe here
  }

}

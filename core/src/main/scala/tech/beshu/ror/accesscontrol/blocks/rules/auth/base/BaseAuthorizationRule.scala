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
package tech.beshu.ror.accesscontrol.blocks.rules.auth.base

import monix.eval.Task
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause.ImpersonationNotSupported
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthorizationRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.SimpleAuthorizationImpersonationSupport.Groups
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{Impersonation, ImpersonationSettings, SimpleAuthorizationImpersonationSupport}
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.LoggedUser.{DirectlyLoggedUser, ImpersonatedUser}
import tech.beshu.ror.accesscontrol.domain.{CaseSensitivity, Group, GroupIdLike, LoggedUser, PermittedGroupIds}
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

private[auth] trait BaseAuthorizationRule
  extends AuthorizationRule
    with SimpleAuthorizationImpersonationSupport {

  protected def userIdCaseSensitivity: CaseSensitivity

  protected def calculateAllowedGroupsForUser(usersGroups: UniqueNonEmptyList[Group]): Option[UniqueNonEmptyList[Group]]

  protected def groupsPermittedByRule: PermittedGroupIds

  protected def userGroups[B <: BlockContext](blockContext: B,
                                              user: LoggedUser,
                                              permittedGroupIds: Set[GroupIdLike])
                                             (implicit requestId: RequestId): Task[UniqueList[Group]]

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
        implicit val requestId: RequestId = blockContext.requestContext.id.toRequestId
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
    mockedGroupsOf(user.id, mocksProvider) match {
      case Groups.Present(mockedGroups) =>
        authorizeLoggedUser(
          blockContext,
          user,
          userGroupsProvider = (_, _, _) => Task.now(mockedGroups)
        )
      case Groups.CannotCheck =>
        Task.now(Rejected(Cause.ImpersonationNotSupported))
    }
  }

  private def authorizeLoggedUser[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                           user: LoggedUser)
                                                                          (implicit requestId: RequestId): Task[RuleResult[B]] = {
    authorizeLoggedUser(
      blockContext,
      user,
      userGroupsProvider = userGroups
    )
  }

  private def authorizeLoggedUser[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                           user: LoggedUser,
                                                                           userGroupsProvider: (B, LoggedUser, Set[GroupIdLike]) => Task[UniqueList[Group]]): Task[RuleResult[B]] = {
    if (blockContext.isCurrentGroupEligible(groupsPermittedByRule)) {
      userGroupsProvider(blockContext, user, groupsPermittedByRule.groupIds.toSet)
        .map(uniqueList => UniqueNonEmptyList.fromIterable(uniqueList.toSet))
        .map {
          case Some(fetchedUserGroups) =>
            calculateAllowedGroupsForUser(fetchedUserGroups) match {
              case Some(availableGroups) =>
                Fulfilled(blockContext.withUserMetadata(
                  _.addAvailableGroups(availableGroups)
                ))
              case None =>
                RuleResult.Rejected()
            }
          case None =>
            RuleResult.Rejected()
        }
    } else {
      Task.now(RuleResult.Rejected())
    }
  }

}

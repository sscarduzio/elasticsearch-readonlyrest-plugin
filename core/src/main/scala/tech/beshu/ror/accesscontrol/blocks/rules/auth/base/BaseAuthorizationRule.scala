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
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.ImpersonationNotSupported
import tech.beshu.ror.accesscontrol.blocks.Decision.{Permitted, Denied}
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthorizationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.SimpleAuthorizationImpersonationSupport.Groups
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{Impersonation, ImpersonationSettings, SimpleAuthorizationImpersonationSupport}
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, Decision}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.LoggedUser.{DirectlyLoggedUser, ImpersonatedUser}
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

import scala.annotation.nowarn

private[auth] trait BaseAuthorizationRule
  extends AuthorizationRule
    with SimpleAuthorizationImpersonationSupport {

  protected def groupsLogic: GroupsLogic

  protected def userIdCaseSensitivity: CaseSensitivity

  protected def userGroups[B <: BlockContext](blockContext: B,
                                              user: LoggedUser)
                                             (implicit requestId: RequestId): Task[UniqueList[Group]]

  @nowarn("msg=unused explicit parameter")
  protected def loggedUserPreconditionCheck(user: LoggedUser): Either[Unit, Unit] = Right(())

  override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] = {
    authorize(blockContext)
  }

  override protected[base] def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] = {
    (blockContext.userMetadata.loggedUser, impersonation) match {
      case (Some(user@ImpersonatedUser(_, _)), Impersonation.Enabled(ImpersonationSettings(_, mocksProvider))) =>
        loggedUserPreconditionCheck(user) match {
          case Left(_) => Task.now(reject())
          case Right(_) => authorizeImpersonatedUser(blockContext, user, mocksProvider)
        }
      case (Some(ImpersonatedUser(_, _)), Impersonation.Disabled) =>
        Task.now(Denied(ImpersonationNotSupported))
      case (Some(user@DirectlyLoggedUser(_)), _) =>
        implicit val requestId: RequestId = blockContext.requestContext.id.toRequestId
        loggedUserPreconditionCheck(user) match {
          case Left(_) => Task.now(reject())
          case Right(_) => doAuthorizeLoggedUser(blockContext, user)
        }
      case (None, _) =>
        Task.now(reject())
    }
  }

  private def authorizeImpersonatedUser[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                                 user: LoggedUser.ImpersonatedUser,
                                                                                 mocksProvider: MocksProvider): Task[Decision[B]] = {
    implicit val requestId: RequestId = blockContext.requestContext.id.toRequestId
    mockedGroupsOf(user.id, mocksProvider) match {
      case Groups.Present(mockedGroups) =>
        authorizeLoggedUser(
          blockContext,
          user,
          fetchUserGroups = (_, _) => Task.now(mockedGroups)
        )
      case Groups.CannotCheck =>
        Task.now(Denied(Cause.ImpersonationNotSupported))
    }
  }

  private def doAuthorizeLoggedUser[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                             user: LoggedUser)
                                                                            (implicit requestId: RequestId): Task[Decision[B]] = {
    authorizeLoggedUser(
      blockContext,
      user,
      fetchUserGroups = userGroups
    )
  }

  private def authorizeLoggedUser[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                           user: LoggedUser,
                                                                           fetchUserGroups: (B, LoggedUser) => Task[UniqueList[Group]]): Task[Decision[B]] = {
    if (blockContext.isCurrentGroupPotentiallyEligible(groupsLogic)) {
      fetchUserGroups(blockContext, user)
        .map(uniqueList => UniqueNonEmptyList.from(uniqueList.toSet))
        .map {
          case Some(fetchedUserGroups) if blockContext.isCurrentGroupEligible(GroupIds.from(fetchedUserGroups)) =>
            calculateAllowedGroupsForUser(fetchedUserGroups) match {
              case Some(availableGroups) =>
                Permitted(blockContext.withUserMetadata(
                  _.addAvailableGroups(availableGroups)
                ))
              case None =>
                reject()
            }
          case None | Some(_) =>
            reject()
        }
    } else {
      Task.now(reject())
    }
  }

  private def calculateAllowedGroupsForUser(usersGroups: UniqueNonEmptyList[Group]): Option[UniqueNonEmptyList[Group]] = {
    groupsLogic.availableGroupsFrom(usersGroups)
  }

  private def reject[T]() = Decision.Denied[T](Cause.GroupsAuthorizationFailed)
}

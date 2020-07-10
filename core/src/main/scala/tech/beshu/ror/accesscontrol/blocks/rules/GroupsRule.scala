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
import cats.data.NonEmptySet
import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.rules.GroupsRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthenticationRule, AuthorizationRule, NoImpersonationSupport, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.Group
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.request.RequestContextOps._
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

// todo:  seems that there is a problem with this rule. Eg. when we use as authentication method JWT token auth.
//        We're trying to get group names first and then check authentication. We cannot resolve JWT token variable
//        when user is not authenticated (using JWT auth token)
class GroupsRule(val settings: Settings)
  extends AuthenticationRule
    with NoImpersonationSupport
    with AuthorizationRule
    with Logging {

  override val name: Rule.Name = GroupsRule.name

  override def tryToAuthenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
    Task
      .unit
      .flatMap { _ =>
        UniqueNonEmptyList.fromList(resolveGroups(blockContext)) match {
          case None => Task.now(Rejected())
          case Some(groups) if blockContext.requestContext.isCurrentGroupEligible(groups) =>
            continueCheckingWithUserDefinitions(blockContext, groups)
          case Some(_) =>
            Task.now(Rejected())
        }
      }

  private def continueCheckingWithUserDefinitions[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                                           resolvedGroups: UniqueNonEmptyList[Group]): Task[RuleResult[B]] = {
    blockContext.userMetadata.loggedUser match {
      case Some(user) =>
        NonEmptySet.fromSet(settings.usersDefinitions.filter(_.id === user.id)) match {
          case None =>
            Task.now(Rejected())
          case Some(filteredUserDefinitions) =>
            tryToAuthorizeAndAuthenticateUsing(filteredUserDefinitions, blockContext, resolvedGroups)
        }
      case None =>
        tryToAuthorizeAndAuthenticateUsing(settings.usersDefinitions, blockContext, resolvedGroups)
    }
  }

  private def tryToAuthorizeAndAuthenticateUsing[B <: BlockContext : BlockContextUpdater](userDefs: NonEmptySet[UserDef],
                                                                                          blockContext: B,
                                                                                          resolvedGroups: UniqueNonEmptyList[Group]): Task[RuleResult[B]] = {
    userDefs
      .reduceLeftTo(authorizeAndAuthenticate(blockContext, resolvedGroups)) {
        case (lastUserDefResult, nextUserDef) =>
          lastUserDefResult.flatMap {
            case success@Some(_) => Task.now(success)
            case None => authorizeAndAuthenticate(blockContext, resolvedGroups)(nextUserDef)
          }
      }
      .map {
        case Some(newBlockContext) => Fulfilled(newBlockContext)
        case None => Rejected()
      }
  }

  private def authorizeAndAuthenticate[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                                resolvedGroups: UniqueNonEmptyList[Group])
                                                                               (userDef: UserDef): Task[Option[B]] = {
    UniqueNonEmptyList.fromSortedSet(userDef.groups.intersect(resolvedGroups)) match {
      case None =>
        Task.now(None)
      case Some(availableGroups) =>
        userDef
          .authenticationRule
          .check(blockContext)
          .map {
            case RuleResult.Rejected(_) =>
              None
            case fulfilled: RuleResult.Fulfilled[B] =>
              val newBlockContext = fulfilled.blockContext
              newBlockContext.userMetadata.loggedUser match {
                case Some(loggedUser) if loggedUser.id === userDef.id => Some {
                  newBlockContext.withUserMetadata(_.addAvailableGroups(availableGroups))
                }
                case Some(_) => None
                case None => None
              }
          }
          .onErrorRecover { case ex =>
            logger.debug(s"Authentication error; req=${blockContext.requestContext.id.show}", ex)
            None
          }
    }
  }

  private def resolveGroups[B <: BlockContext](blockContext: B) = {
    resolveAll(settings.groups.toNonEmptyList, blockContext)
  }
}

object GroupsRule {
  val name = Rule.Name("groups")

  final case class Settings(groups: UniqueNonEmptyList[RuntimeMultiResolvableVariable[Group]],
                            usersDefinitions: NonEmptySet[UserDef])
}

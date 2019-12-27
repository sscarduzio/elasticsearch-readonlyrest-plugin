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
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.rules.GroupsRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthenticationRule, AuthorizationRule, NoImpersonationSupport, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.domain.Group
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.request.RequestContext.Id._
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

  override def tryToAuthenticate(requestContext: RequestContext,
                                 blockContext: BlockContext): Task[RuleResult] = Task.unit
    .flatMap { _ =>
      UniqueNonEmptyList.fromList(resolveGroups(requestContext, blockContext)) match {
        case None => Task.now(Rejected())
        case Some(groups) if requestContext.isCurrentGroupEligible(groups) =>
          continueCheckingWithUserDefinitions(requestContext, blockContext, groups)
        case Some(_) =>
          Task.now(Rejected())
      }
    }

  private def continueCheckingWithUserDefinitions(requestContext: RequestContext,
                                                  blockContext: BlockContext,
                                                  resolvedGroups: UniqueNonEmptyList[Group]): Task[RuleResult] = {
    blockContext.loggedUser match {
      case Some(user) =>
        NonEmptySet.fromSet(settings.usersDefinitions.filter(_.id === user.id)) match {
          case None =>
            Task.now(Rejected())
          case Some(filteredUserDefinitions) =>
            tryToAuthorizeAndAuthenticateUsing(filteredUserDefinitions, requestContext, blockContext, resolvedGroups)
        }
      case None =>
        tryToAuthorizeAndAuthenticateUsing(settings.usersDefinitions, requestContext, blockContext, resolvedGroups)
    }
  }

  private def tryToAuthorizeAndAuthenticateUsing(userDefs: NonEmptySet[UserDef],
                                                 requestContext: RequestContext,
                                                 blockContext: BlockContext,
                                                 resolvedGroups: UniqueNonEmptyList[Group]) = {
    userDefs
      .reduceLeftTo(authorizeAndAuthenticate(requestContext, blockContext, resolvedGroups)) {
        case (lastUserDefResult, nextUserDef) =>
          lastUserDefResult.flatMap {
            case success@Some(_) => Task.now(success)
            case None => authorizeAndAuthenticate(requestContext, blockContext, resolvedGroups)(nextUserDef)
          }
      }
      .map {
        case Some(newBlockContext) => Fulfilled(newBlockContext)
        case None => Rejected()
      }
  }

  private def authorizeAndAuthenticate(requestContext: RequestContext,
                                       blockContext: BlockContext,
                                       resolvedGroups: UniqueNonEmptyList[Group])
                                      (userDef: UserDef) = {
    UniqueNonEmptyList.fromSortedSet(userDef.groups.intersect(resolvedGroups)) match {
      case None =>
        Task.now(None)
      case Some(availableGroups) =>
        userDef
          .authenticationRule
          .check(requestContext, blockContext)
          .map {
            case RuleResult.Rejected(_) =>
              None
            case RuleResult.Fulfilled(newBlockContext) =>
              newBlockContext.loggedUser match {
                case Some(loggedUser) if loggedUser.id === userDef.id => Some {
                  newBlockContext.withAddedAvailableGroups(availableGroups)
                }
                case Some(_) => None
                case None => None
              }
          }
          .onErrorRecover { case ex =>
            logger.debug(s"Authentication error; req=${requestContext.id.show}", ex)
            None
          }
    }
  }

  private def resolveGroups(requestContext: RequestContext,
                            blockContext: BlockContext) = {
    resolveAll(settings.groups.toNonEmptyList, requestContext, blockContext)
  }
}

object GroupsRule {
  val name = Rule.Name("groups")

  final case class Settings(groups: UniqueNonEmptyList[RuntimeMultiResolvableVariable[Group]],
                            usersDefinitions: NonEmptySet[UserDef])
}

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

import cats.data.NonEmptyList
import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode.WithGroupsMapping.Auth
import tech.beshu.ror.accesscontrol.blocks.rules.GroupsRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule._
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.{Group, User}
import tech.beshu.ror.accesscontrol.matchers.GenericPatternMatcher
import tech.beshu.ror.accesscontrol.request.RequestContextOps._
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

final class GroupsRule(val settings: Settings,
                       implicit override val caseMappingEquality: UserIdCaseMappingEquality)
  extends AuthRule
    with NoImpersonationSupport
    with Logging {

  override val name: Rule.Name = GroupsRule.Name.name

  override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable

  private val matchers = settings
    .usersDefinitions.toList
    .map { userDef => userDef -> new GenericPatternMatcher(userDef.usernames.patterns.toList) }
    .toMap

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
        NonEmptyList.fromFoldable(userDefinitionsMatching(user.id)) match {
          case None =>
            Task.now(Rejected())
          case Some(filteredUserDefinitions) =>
            tryToAuthorizeAndAuthenticateUsing(filteredUserDefinitions, blockContext, resolvedGroups)
        }
      case None =>
        tryToAuthorizeAndAuthenticateUsing(settings.usersDefinitions, blockContext, resolvedGroups)
    }
  }

  private def userDefinitionsMatching(userId: User.Id) = {
    settings.usersDefinitions.filter(ud => matchers(ud).`match`(userId))
  }

  private def tryToAuthorizeAndAuthenticateUsing[B <: BlockContext : BlockContextUpdater](userDefs: NonEmptyList[UserDef],
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
        val allowedUserMatcher = matchers(userDef)
        userDef.mode match {
          case Mode.WithoutGroupsMapping(auth) =>
            authenticate(auth, blockContext, allowedUserMatcher, availableGroups, withGroupsMapping = false)
          case Mode.WithGroupsMapping(Auth.SingleRule(auth)) =>
            authenticateAndAuthorize(
              auth = auth,
              blockContext = blockContext,
              allowedUserMatcher = allowedUserMatcher,
              availableGroups = availableGroups,
              withGroupsMapping = true
            )
          case Mode.WithGroupsMapping(Auth.SeparateRules(authn, authz)) =>
            authenticateAndAuthorize(
              authnRule = authn,
              authzRule = authz,
              blockContext = blockContext,
              allowedUserMatcher = allowedUserMatcher,
              availableGroups = availableGroups,
              withGroupsMapping = true
            )
        }
    }
  }

  private def authenticate[B <: BlockContext : BlockContextUpdater](auth: AuthenticationRule,
                                                                    blockContext: B,
                                                                    allowedUserMatcher: GenericPatternMatcher[User.Id],
                                                                    availableGroups: UniqueNonEmptyList[Group],
                                                                    withGroupsMapping: Boolean) = {
    checkRule(auth, blockContext, allowedUserMatcher, availableGroups, withGroupsMapping)
      .onErrorRecover { case ex =>
        logger.debug(s"Authentication error; req=${blockContext.requestContext.id.show}", ex)
        None
      }
  }

  private def authenticateAndAuthorize[B <: BlockContext : BlockContextUpdater](auth: AuthRule,
                                                                                blockContext: B,
                                                                                allowedUserMatcher: GenericPatternMatcher[User.Id],
                                                                                availableGroups: UniqueNonEmptyList[Group],
                                                                                withGroupsMapping: Boolean) = {
    checkRule(auth, blockContext, allowedUserMatcher, availableGroups, withGroupsMapping)
      .onErrorRecover { case ex =>
        logger.debug(s"Authentication & Authorization error; req=${blockContext.requestContext.id.show}", ex)
        None
      }
  }

  private def authenticateAndAuthorize[B <: BlockContext : BlockContextUpdater](authnRule: AuthenticationRule,
                                                                                authzRule: AuthorizationRule,
                                                                                blockContext: B,
                                                                                allowedUserMatcher: GenericPatternMatcher[User.Id],
                                                                                availableGroups: UniqueNonEmptyList[Group],
                                                                                withGroupsMapping: Boolean): Task[Option[B]] = {
    checkRule(authnRule, blockContext, allowedUserMatcher, availableGroups, withGroupsMapping)
      .flatMap {
        case Some(newBlockContext) =>
          authzRule
            .check(newBlockContext)
            .map {
              case _: RuleResult.Fulfilled[B] => Some(newBlockContext)
              case RuleResult.Rejected(_) => None
            }
        case None =>
          Task.now(Option.empty[B])
      }
      .onErrorRecover { case ex =>
        logger.debug(s"Authentication & Authorization error; req=${blockContext.requestContext.id.show}", ex)
        Option.empty[B]
      }
  }

  private def checkRule[B <: BlockContext : BlockContextUpdater](rule: Rule,
                                                                 blockContext: B,
                                                                 allowedUserMatcher: GenericPatternMatcher[User.Id],
                                                                 availableGroups: UniqueNonEmptyList[Group],
                                                                 withGroupsMapping: Boolean) = {
    rule
      .check(
        if(withGroupsMapping) blockContext.withUserMetadata(_.clearCurrentGroup)
        else blockContext
      )
      .map {
        case RuleResult.Rejected(_) =>
          None
        case fulfilled: RuleResult.Fulfilled[B] =>
          val newBlockContext = fulfilled.blockContext
          newBlockContext.userMetadata.loggedUser match {
            case Some(loggedUser) if allowedUserMatcher.`match`(loggedUser.id) => Some {
              blockContext.withUserMetadata(_
                .withLoggedUser(loggedUser)
                .addAvailableGroups(availableGroups))
            }
            case Some(_) => None
            case None => None
          }
      }
  }

  private def resolveGroups[B <: BlockContext](blockContext: B) = {
    resolveAll(settings.groups.toNonEmptyList, blockContext)
  }
}

object GroupsRule {

  implicit case object Name extends RuleName[GroupsRule] {
    override val name = Rule.Name("groups")
  }

  final case class Settings(groups: UniqueNonEmptyList[RuntimeMultiResolvableVariable[Group]],
                            usersDefinitions: NonEmptyList[UserDef])
}

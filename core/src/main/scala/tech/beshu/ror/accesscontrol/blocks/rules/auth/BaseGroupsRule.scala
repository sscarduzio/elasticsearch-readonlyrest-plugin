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
package tech.beshu.ror.accesscontrol.blocks.rules.auth

import cats.data.NonEmptyList
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.Mode.WithGroupsMapping.Auth
import tech.beshu.ror.accesscontrol.blocks.definitions.UserDef.{GroupMappings, Mode}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthRule, AuthenticationRule, AuthorizationRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.BaseGroupsRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{AuthenticationImpersonationCustomSupport, AuthorizationImpersonationCustomSupport}
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.{Group, PermittedGroupIds, ResolvablePermittedGroupIds, User}
import tech.beshu.ror.accesscontrol.matchers.GenericPatternMatcher
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

abstract class BaseGroupsRule(val settings: Settings)
  extends AuthRule
    with AuthenticationImpersonationCustomSupport
    with AuthorizationImpersonationCustomSupport
    with Logging {

  protected def calculateAllowedGroupsForUser(userGroups: UniqueNonEmptyList[Group],
                                              permittedGroupIds: PermittedGroupIds): Option[UniqueNonEmptyList[Group]]

  override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable

  private val matchers = settings
    .usersDefinitions.toList
    .map { userDef => userDef -> new GenericPatternMatcher(userDef.usernames.patterns.toList) }
    .toMap

  override protected def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = {
    Task
      .unit
      .flatMap { _ =>
        resolveGroupIds(blockContext) match {
          case None =>
            Task.now(Rejected())
          case Some(resolvedPermittedGroupIds) if blockContext.isCurrentGroupEligible(resolvedPermittedGroupIds) =>
            continueCheckingWithUserDefinitions(blockContext, resolvedPermittedGroupIds)
          case Some(_) =>
            Task.now(Rejected())
        }
      }
  }

  override protected def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
    Task.now(RuleResult.Fulfilled(blockContext))

  private def continueCheckingWithUserDefinitions[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                                           resolvedPermittedGroupIds: PermittedGroupIds): Task[RuleResult[B]] = {
    blockContext.userMetadata.loggedUser match {
      case Some(user) =>
        NonEmptyList.fromFoldable(userDefinitionsMatching(user.id)) match {
          case None =>
            Task.now(Rejected())
          case Some(filteredUserDefinitions) =>
            tryToAuthorizeAndAuthenticateUsing(filteredUserDefinitions, blockContext, resolvedPermittedGroupIds)
        }
      case None =>
        tryToAuthorizeAndAuthenticateUsing(settings.usersDefinitions, blockContext, resolvedPermittedGroupIds)
    }
  }

  private def userDefinitionsMatching(userId: User.Id) = {
    settings.usersDefinitions.filter(ud => matchers(ud).`match`(userId))
  }

  private def tryToAuthorizeAndAuthenticateUsing[B <: BlockContext : BlockContextUpdater](userDefs: NonEmptyList[UserDef],
                                                                                          blockContext: B,
                                                                                          resolvedPermittedGroupIds: PermittedGroupIds): Task[RuleResult[B]] = {
    userDefs
      .reduceLeftTo(authorizeAndAuthenticate(blockContext, resolvedPermittedGroupIds)) {
        case (lastUserDefResult, nextUserDef) =>
          lastUserDefResult.flatMap {
            case success@Some(_) => Task.now(success)
            case None => authorizeAndAuthenticate(blockContext, resolvedPermittedGroupIds)(nextUserDef)
          }
      }
      .map {
        case Some(newBlockContext) => Fulfilled(newBlockContext)
        case None => Rejected()
      }
  }

  private def authorizeAndAuthenticate[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                                resolvedPermittedGroupIds: PermittedGroupIds)
                                                                               (userDef: UserDef): Task[Option[B]] = {
    calculateAllowedGroupsForUser(userDef.localGroups, resolvedPermittedGroupIds) match {
      case None =>
        Task.now(None)
      case Some(availableGroups) =>
        val allowedUserMatcher = matchers(userDef)
        userDef.mode match {
          case Mode.WithoutGroupsMapping(auth, _) =>
            authenticate(auth, blockContext, allowedUserMatcher, availableGroups, userDef.mode)
          case Mode.WithGroupsMapping(Auth.SingleRule(auth), groupMappings) =>
            authenticateAndAuthorize(
              auth,
              groupMappings,
              blockContext,
              allowedUserMatcher,
              availableGroups,
              userDef.mode
            )
          case Mode.WithGroupsMapping(Auth.SeparateRules(authn, authz), groupMappings) =>
            authenticateAndAuthorize(
              authnRule = authn,
              authzRule = authz,
              groupMappings,
              blockContext,
              allowedUserMatcher,
              availableGroups,
              userDef.mode
            )
        }
    }
  }

  private def authenticate[B <: BlockContext : BlockContextUpdater](auth: AuthenticationRule,
                                                                    blockContext: B,
                                                                    allowedUserMatcher: GenericPatternMatcher[User.Id],
                                                                    availableGroups: UniqueNonEmptyList[Group],
                                                                    mode: Mode) = {
    checkRule(auth, blockContext, allowedUserMatcher, mode)
      .map {
        case Some(newBlockContext) =>
          newBlockContext
            .userMetadata.loggedUser
            .map { loggedUser =>
              blockContext.withUserMetadata(_
                .withLoggedUser(loggedUser)
                .withAvailableGroups(UniqueList.from(availableGroups))
              )
            }
        case None =>
          None
      }
      .onErrorRecover { case ex =>
        logger.debug(s"[${blockContext.requestContext.id.show}] Authentication error", ex)
        None
      }
  }

  private def authenticateAndAuthorize[B <: BlockContext : BlockContextUpdater](auth: AuthRule,
                                                                                groupMappings: GroupMappings,
                                                                                blockContext: B,
                                                                                allowedUserMatcher: GenericPatternMatcher[User.Id],
                                                                                availableGroups: UniqueNonEmptyList[Group],
                                                                                mode: Mode) = {
    checkRule(auth, blockContext, allowedUserMatcher, mode)
      .map {
        case Some(newBlockContext) =>
          updateBlockContextWithLoggedUserAndAllowedGroups(
            sourceBlockContext = newBlockContext,
            destinationBlockContext = blockContext,
            potentiallyAvailableGroups = availableGroups,
            groupMappings = groupMappings
          )
        case None =>
          None
      }
      .onErrorRecover { case ex =>
        logger.debug(s"[${blockContext.requestContext.id.show}] Authentication & Authorization error", ex)
        None
      }
  }

  private def authenticateAndAuthorize[B <: BlockContext : BlockContextUpdater](authnRule: AuthenticationRule,
                                                                                authzRule: AuthorizationRule,
                                                                                groupMappings: GroupMappings,
                                                                                blockContext: B,
                                                                                allowedUserMatcher: GenericPatternMatcher[User.Id],
                                                                                availableGroups: UniqueNonEmptyList[Group],
                                                                                mode: Mode): Task[Option[B]] = {
    checkRule(authnRule, blockContext, allowedUserMatcher, mode)
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
      .map {
        case Some(newBlockContext) =>
          updateBlockContextWithLoggedUserAndAllowedGroups(
            sourceBlockContext = newBlockContext,
            destinationBlockContext = blockContext,
            potentiallyAvailableGroups = availableGroups,
            groupMappings = groupMappings
          )
        case None =>
          None
      }
      .onErrorRecover { case ex =>
        logger.debug(s"[${blockContext.requestContext.id.show}] Authentication & Authorization error", ex)
        Option.empty[B]
      }
  }

  private def updateBlockContextWithLoggedUserAndAllowedGroups[B <: BlockContext : BlockContextUpdater](sourceBlockContext: B,
                                                                                                        destinationBlockContext: B,
                                                                                                        potentiallyAvailableGroups: UniqueNonEmptyList[Group],
                                                                                                        groupMappings: GroupMappings) = {
    val externalAvailableGroups = sourceBlockContext.userMetadata.availableGroups
    for {
      externalGroupsMappedToLocalGroups <- mapExternalGroupsToLocalGroups(groupMappings, externalAvailableGroups)
      availableLocalGroups <- calculateAllowedGroupsForUser(potentiallyAvailableGroups, PermittedGroupIds(externalGroupsMappedToLocalGroups))
      loggedUser <- sourceBlockContext.userMetadata.loggedUser
    } yield destinationBlockContext.withUserMetadata(_
      .withLoggedUser(loggedUser)
      .withAvailableGroups(UniqueList.from(availableLocalGroups))
    )
  }

  private def checkRule[B <: BlockContext : BlockContextUpdater](rule: Rule,
                                                                 blockContext: B,
                                                                 allowedUserMatcher: GenericPatternMatcher[User.Id],
                                                                 mode: Mode) = {
    val initialBlockContext = mode match {
      case Mode.WithGroupsMapping(_, _) => blockContext.withUserMetadata(_.clearCurrentGroup)
      case Mode.WithoutGroupsMapping(_, _) => blockContext
    }
    rule
      .check(initialBlockContext)
      .map {
        case RuleResult.Rejected(_) =>
          None
        case fulfilled: RuleResult.Fulfilled[B] =>
          val newBlockContext = fulfilled.blockContext
          newBlockContext.userMetadata.loggedUser match {
            case Some(loggedUser) if allowedUserMatcher.`match`(loggedUser.id) => Some(newBlockContext)
            case Some(_) => None
            case None => None
          }
      }
  }

  private def mapExternalGroupsToLocalGroups(groupMappings: GroupMappings,
                                             externalGroup: UniqueList[Group]) = {
    groupMappings match {
      case GroupMappings.Simple(localGroups) => UniqueNonEmptyList.from(localGroups.map(_.id))
      case GroupMappings.Advanced(mappings) => UniqueNonEmptyList.from {
        externalGroup
          .toList
          .flatMap { externalGroup =>
            mappings
              .toList
              .filter(m => m.externalGroupIdPatternsMatcher.`match`(externalGroup.id))
              .map(_.local)
          }
          .map(_.id)
      }
    }
  }

  private def resolveGroupIds[B <: BlockContext](blockContext: B) = {
    settings.permittedGroupIds.resolveGroupIds(blockContext)
  }
}

object BaseGroupsRule {

  final case class Settings(permittedGroupIds: ResolvablePermittedGroupIds,
                            usersDefinitions: NonEmptyList[UserDef])
}
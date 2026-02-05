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

import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.GroupsAuthorizationFailed
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDefForAuthorization
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthorizationRule, RuleName}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.JwtAuthorizationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseJwtRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.AuthorizationImpersonationCustomSupport
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, Decision}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.{ClaimSearchResult, toClaimsOps}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

final class JwtAuthorizationRule(val settings: Settings)
  extends AuthorizationRule
    with AuthorizationImpersonationCustomSupport
    with BaseJwtRule {

  override val name: Rule.Name = JwtAuthorizationRule.Name.name

  override protected[rules] def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] = {
    if (blockContext.isCurrentGroupPotentiallyEligible(settings.groupsLogic)) {
      processUsingJwtToken(blockContext, settings.jwt, GroupsAuthorizationFailed.apply) { payload =>
        authorize(blockContext, payload)
      }
    } else {
      Task.now(Decision.Denied(Cause.GroupsAuthorizationFailed("Current group is not allowed")))
    }
  }

  override protected[rules] def postAuthorizationAction[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] = {
    doPostAuthAction(blockContext, settings.jwt, GroupsAuthorizationFailed.apply)
  }

  private def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                 payload: Jwt.Payload) = {
    implicit val blockContextImpl: B = blockContext
    val groupsConfig = settings.jwt.groupsConfig
    val groupsTokenSearchResult = payload.claims.groupsClaim(groupsConfig.idsClaim, groupsConfig.namesClaim)
    logClaimSearchResults(blockContext, groupsTokenSearchResult)
    for {
      userGroups <- groupsFrom(groupsTokenSearchResult)
      matchedGroups <- settings.groupsLogic
        .availableGroupsFrom(userGroups)
        .toRight(Cause.GroupsAuthorizationFailed("None of the user's groups match the configured groups"))
      _ <- Either.cond(
        blockContext.isCurrentGroupEligible(GroupIds.from(matchedGroups)),
        (), Cause.GroupsAuthorizationFailed("Current group is not allowed")
      )
    } yield {
      blockContext.withUserMetadata(
        _.addAvailableGroups(matchedGroups).withJwtToken(payload)
      )
    }
  }

  private def logClaimSearchResults[B <: BlockContext](blockContext: B,
                                                       groups: ClaimSearchResult[UniqueList[Group]]): Unit = {
    implicit val blockContextImpl: B = blockContext
    val claimsDescription = settings.jwt.groupsConfig.namesClaim match {
      case Some(namesClaim) => s"claims (id:'${settings.jwt.groupsConfig.idsClaim.name.show}',name:'${namesClaim.name.show}')"
      case None => s"claim '${settings.jwt.groupsConfig.idsClaim.name.show}'"
    }
    logger.debug(s"JWT resolved groups for ${claimsDescription.show}: ${groups.show}")
  }


  private def groupsFrom(groupsFromToken: ClaimSearchResult[UniqueList[Group]]) = {
    for {
      groups <- groupsFromToken.toEither.left.map { case () => GroupsAuthorizationFailed("Groups claim not found in JWT") }
      nonEmptyGroups <- UniqueNonEmptyList.from(groups).toRight(Cause.GroupsAuthorizationFailed("No groups found in JWT token"))
    } yield nonEmptyGroups
  }
}

object JwtAuthorizationRule {

  implicit case object Name extends RuleName[JwtAuthorizationRule] {
    override val name = Rule.Name("jwt_authorization")
  }

  final case class Settings(jwt: JwtDefForAuthorization, groupsLogic: GroupsLogic)
}

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
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDefForAuthorization
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthorizationRule, RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.*
import tech.beshu.ror.accesscontrol.blocks.rules.auth.JwtAuthorizationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseJwtRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.AuthorizationImpersonationCustomSupport
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.ClaimSearchResult.*
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.{ClaimSearchResult, toClaimsOps}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

final class JwtAuthorizationRule(val settings: Settings)
  extends AuthorizationRule
    with AuthorizationImpersonationCustomSupport
    with BaseJwtRule {

  override val name: Rule.Name = JwtAuthorizationRule.Name.name

  override protected[rules] def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = {
    settings.groupsLogic match {
      case groupsLogic if blockContext.isCurrentGroupPotentiallyEligible(groupsLogic) =>
        processUsingJwtToken(blockContext, settings.jwt) { payload =>
          authorize(blockContext, payload, groupsLogic)
        }
      case _ =>
        Task.now(RuleResult.Rejected())
    }
  }

  override protected[rules] def postAuthorizationAction[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = {
    doPostAuthAction(blockContext, settings.jwt)
  }

  private def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                 payload: Jwt.Payload,
                                                                 groupsLogic: GroupsLogic): RuleResult[B] = {
    implicit val blockContextImpl: B = blockContext
    val groupsConfig = settings.jwt.groupsConfig
    val result = payload.claims.groupsClaim(groupsConfig.idsClaim, groupsConfig.namesClaim)
    logClaimSearchResults(blockContext, result)
    result match {
      case NotFound =>
        RuleResult.Rejected()
      case Found(groups) =>
        for {
          nonEmptyGroups <- RuleResult.fromOption(UniqueNonEmptyList.from(groups))
          matchedGroups <- RuleResult.fromOption(groupsLogic.availableGroupsFrom(nonEmptyGroups))
          if blockContext.isCurrentGroupEligible(GroupIds.from(matchedGroups))
          contextWithBlockMetadata = blockContext.withBlockMetadata(
            _.addAvailableGroups(matchedGroups).withJwtToken(payload)
          )
        } yield contextWithBlockMetadata
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

}

object JwtAuthorizationRule {

  implicit case object Name extends RuleName[JwtAuthorizationRule] {
    override val name = Rule.Name("jwt_authorization")
  }

  final case class Settings(jwt: JwtDefForAuthorization, groupsLogic: GroupsLogic)
}

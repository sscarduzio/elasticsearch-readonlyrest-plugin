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
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthorizationRule, RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.JwtAuthorizationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseJwtRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.AuthorizationImpersonationCustomSupport
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.{Group, GroupIds, GroupsLogic}
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.ClaimSearchResult
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.ClaimSearchResult.*
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

final class JwtAuthorizationRule(val settings: Settings)
  extends AuthorizationRule
    with AuthorizationImpersonationCustomSupport
    with BaseJwtRule {

  override val name: Rule.Name = JwtAuthorizationRule.Name.name

  override protected[rules] def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = {
    settings.groupsLogic match {
      case groupsLogic if blockContext.isCurrentGroupPotentiallyEligible(groupsLogic) =>
        processUsingJwtToken(blockContext, settings.jwt) { tokenData =>
          authorize(blockContext, tokenData.groups, groupsLogic)
        }
      case _ =>
        Task.now(RuleResult.Rejected())
    }
  }

  private def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                 result: Option[ClaimSearchResult[UniqueList[Group]]],
                                                                 groupsLogic: GroupsLogic) = {
    result match {
      case None | Some(NotFound) =>
        Left(())
      case Some(Found(groups)) =>
        (for {
          nonEmptyGroups <- UniqueNonEmptyList.from(groups)
          matchedGroups <- groupsLogic.availableGroupsFrom(nonEmptyGroups)
          if blockContext.isCurrentGroupEligible(GroupIds.from(matchedGroups))
        } yield blockContext.withUserMetadata(_.addAvailableGroups(matchedGroups))).toRight(())
    }
  }

}

object JwtAuthorizationRule {

  implicit case object Name extends RuleName[JwtAuthorizationRule] {
    override val name = Rule.Name("jwt_authorization")
  }

  final case class Settings(jwt: JwtDef, groupsLogic: GroupsLogic)
}

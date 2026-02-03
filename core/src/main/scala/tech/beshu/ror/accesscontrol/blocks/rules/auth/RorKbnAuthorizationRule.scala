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
import tech.beshu.ror.accesscontrol.blocks.definitions.RorKbnDef
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthorizationRule, RuleName}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.RorKbnAuthorizationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseRorKbnRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.AuthorizationImpersonationCustomSupport
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, Decision}
import tech.beshu.ror.accesscontrol.domain.{Group, GroupIds, GroupsLogic}
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.ClaimSearchResult
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

final class RorKbnAuthorizationRule(val settings: Settings)
  extends AuthorizationRule
    with AuthorizationImpersonationCustomSupport
    with BaseRorKbnRule {

  override val name: Rule.Name = RorKbnAuthorizationRule.Name.name

  override protected[rules] def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[Decision[B]] = Task.delay {
    if (isCurrentGroupPotentiallyEligible(blockContext)) {
      processUsingJwtToken(blockContext, settings.rorKbn) { tokenData =>
        authorize(blockContext, tokenData.groups)
      }
    } else {
      Decision.Denied(Cause.GroupsAuthorizationFailed("Current group is not potentially eligible"))
    }
  }

  private def isCurrentGroupPotentiallyEligible(blockContext: BlockContext) = {
    blockContext.isCurrentGroupPotentiallyEligible(settings.groupsLogic)
  }

  private def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                 groupsFromToken: ClaimSearchResult[UniqueList[Group]]) = {
    for {
      groups <- groupsFromToken.toEither.left.map { case () => GroupsAuthorizationFailed("Groups claim not found in ROR Kibana token")}
      nonEmptyGroups <- UniqueNonEmptyList.from(groups).toRight(GroupsAuthorizationFailed("No groups found in ROR Kibana token"))
      matchedGroups <- settings.groupsLogic.availableGroupsFrom(nonEmptyGroups).toRight(GroupsAuthorizationFailed("No matching groups from groups logic"))
      _ <- Either.cond(
        blockContext.isCurrentGroupEligible(GroupIds.from(matchedGroups)),
        (),
        GroupsAuthorizationFailed("Current group is not in matched groups")
      )
    } yield {
      blockContext.withUserMetadata(_.addAvailableGroups(matchedGroups))
    }
  }

}

object RorKbnAuthorizationRule {

  implicit case object Name extends RuleName[RorKbnAuthorizationRule] {
    override val name = Rule.Name("ror_kbn_authorization")
  }

  final case class Settings(rorKbn: RorKbnDef, groupsLogic: GroupsLogic)
}

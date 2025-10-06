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
import tech.beshu.ror.accesscontrol.blocks.definitions.RorKbnDef
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthorizationRule, RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.RorKbnAuthorizationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseRorKbnRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.AuthorizationImpersonationCustomSupport
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.{Group, GroupIds, GroupsLogic}
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.ClaimSearchResult
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.ClaimSearchResult.{Found, NotFound}
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

final class RorKbnAuthorizationRule(val settings: Settings)
  extends AuthorizationRule
    with AuthorizationImpersonationCustomSupport
    with BaseRorKbnRule {

  override val name: Rule.Name = RorKbnAuthorizationRule.Name.name

  override protected[rules] def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task.delay {
    settings.groupsLogic match {
      case Some(groupsLogic) if blockContext.isCurrentGroupPotentiallyEligible(groupsLogic) =>
        processUsingJwtToken(blockContext, settings.rorKbn) { tokenData =>
          authorize(blockContext, tokenData.groups, settings.groupsLogic)
        }
      case None =>
        processUsingJwtToken(blockContext, settings.rorKbn) { tokenData =>
          authorize(blockContext, tokenData.groups, settings.groupsLogic)
        }
      case Some(_) =>
        RuleResult.Rejected()
    }
  }

  private def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                 result: ClaimSearchResult[UniqueList[Group]],
                                                                 groupsLogic: Option[GroupsLogic]) = {
    (result, groupsLogic) match {
      case (NotFound, _) =>
        Left(())
      case (Found(groups), Some(groupsLogic)) =>
        UniqueNonEmptyList.from(groups) match {
          case Some(nonEmptyGroups) =>
            groupsLogic.availableGroupsFrom(nonEmptyGroups) match {
              case Some(matchedGroups) if blockContext.isCurrentGroupEligible(GroupIds.from(matchedGroups)) =>
                Right(blockContext.withUserMetadata(_.addAvailableGroups(matchedGroups)))
              case Some(_) | None =>
                Left(())
            }
          case None =>
            Left(())
        }
      case (Found(groups), None) =>
        UniqueNonEmptyList.from(groups) match {
          case None =>
            Right(blockContext)
          case Some(nonEmptyGroups) if blockContext.isCurrentGroupEligible(GroupIds.from(nonEmptyGroups)) =>
            Right(blockContext)
          case Some(_) | None =>
            Left(())
        }
    }
  }

}

object RorKbnAuthorizationRule {

  implicit case object Name extends RuleName[RorKbnAuthorizationRule] {
    override val name = Rule.Name("ror_kbn_authorization")
  }

  final case class Settings(rorKbn: RorKbnDef, groupsLogic: Option[GroupsLogic])
}

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
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthenticationRule, RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.RorKbnAuthenticationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseRorKbnRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.AuthenticationImpersonationCustomSupport
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.ClaimSearchResult
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.ClaimSearchResult.{Found, NotFound}
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

final class RorKbnAuthenticationRule(val settings: Settings,
                                     override val userIdCaseSensitivity: CaseSensitivity)
  extends AuthenticationRule
    with AuthenticationImpersonationCustomSupport
    with BaseRorKbnRule {

  override val name: Rule.Name = RorKbnAuthenticationRule.Name.name

  override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable

  override protected[rules] def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task.delay {
    processUsingJwtToken(blockContext, settings.rorKbn) { tokenData =>
      authenticate(blockContext, tokenData.userId, tokenData.groups, tokenData.userOrigin, tokenData.payload)
    }
  }

  private def authenticate[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                    userId: ClaimSearchResult[User.Id],
                                                                    groups: ClaimSearchResult[UniqueList[Group]],
                                                                    userOrigin: ClaimSearchResult[Header],
                                                                    tokenPayload: Jwt.Payload): Either[Unit, B] = {
    userId match {
      case Found(userId) =>
        val withUserMetadata = userOrigin match {
          case Found(header) =>
            blockContext.withUserMetadata(
              _
                .withLoggedUser(DirectlyLoggedUser(userId))
                .withUserOrigin(UserOrigin(header.value))
                .withJwtToken(tokenPayload)
            )
          case ClaimSearchResult.NotFound =>
            blockContext.withUserMetadata(
              _
                .withLoggedUser(DirectlyLoggedUser(userId))
                .withJwtToken(tokenPayload)
            )
        }
        handleGroupsClaimSearchResult(withUserMetadata, groups)
      case NotFound =>
        Left(())
    }
  }

  private def handleGroupsClaimSearchResult[B <: BlockContext](blockContext: B,
                                                               groups: ClaimSearchResult[UniqueList[Group]]): Either[Unit, B] = {
    groups match {
      case NotFound =>
        Right(blockContext) // if groups field is not found, we treat this situation as same as empty groups would be passed
      case Found(groups) =>
        UniqueNonEmptyList.from(groups) match {
          case None =>
            Right(blockContext)
          case Some(nonEmptyGroups) if blockContext.isCurrentGroupEligible(GroupIds.from(nonEmptyGroups)) =>
            Right(blockContext)
          case Some(_) =>
            Left(())
        }
    }
  }

}

object RorKbnAuthenticationRule {

  implicit case object Name extends RuleName[RorKbnAuthenticationRule] {
    override val name = Rule.Name("ror_kbn_authentication")
  }

  final case class Settings(rorKbn: RorKbnDef)
}

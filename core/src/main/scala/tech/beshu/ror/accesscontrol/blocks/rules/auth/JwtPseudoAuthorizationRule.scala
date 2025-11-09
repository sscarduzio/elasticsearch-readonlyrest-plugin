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
import tech.beshu.ror.accesscontrol.blocks.rules.auth.JwtPseudoAuthorizationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseJwtRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.AuthorizationImpersonationCustomSupport
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.{Group, GroupIds}
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.ClaimSearchResult
import tech.beshu.ror.accesscontrol.utils.ClaimsOps.ClaimSearchResult.*
import tech.beshu.ror.utils.uniquelist.{UniqueList, UniqueNonEmptyList}

// Pseudo-authorization rule should be used exclusively as part of the JwtAuthRule, when there are is no groups logic defined.
final class JwtPseudoAuthorizationRule(val settings: Settings)
  extends AuthorizationRule
    with AuthorizationImpersonationCustomSupport
    with BaseJwtRule {

  override val name: Rule.Name = JwtAuthorizationRule.Name.name

  override protected[rules] def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = {
    processUsingJwtToken(blockContext, settings.jwt) { tokenData =>
      pseudoAuthorize(blockContext, tokenData.groups)
    }
  }

  private def pseudoAuthorize[B <: BlockContext](blockContext: B,
                                                 result: Option[ClaimSearchResult[UniqueList[Group]]]) = {
    result match {
      case None | Some(NotFound) =>
        Right(blockContext)
      case Some(Found(groups)) =>
        (for {
          nonEmptyGroups <- UniqueNonEmptyList.from(groups)
          if blockContext.isCurrentGroupEligible(GroupIds.from(nonEmptyGroups))
        } yield blockContext).toRight(())
    }
  }

}

object JwtPseudoAuthorizationRule {

  implicit case object Name extends RuleName[JwtAuthorizationRule] {
    override val name = Rule.Name("jwt_authorization")
  }

  final case class Settings(jwt: JwtDef)
}

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
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.definitions.RorKbnDef
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthorizationRule, RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.RorKbnAuthorizationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseRorKbnRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseRorKbnRule.RorKbnOperation.Authorize
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{AuthenticationImpersonationCustomSupport, AuthorizationImpersonationCustomSupport}
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.GroupsLogic

final class RorKbnAuthorizationRule(val settings: Settings)
  extends AuthorizationRule
    with AuthenticationImpersonationCustomSupport
    with AuthorizationImpersonationCustomSupport
    with Logging {

  override val name: Rule.Name = RorKbnAuthorizationRule.Name.name

  override protected[rules] def authorize[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] =
    Task {
      settings.groupsLogic match {
        case groupsLogic if blockContext.isCurrentGroupPotentiallyEligible(groupsLogic) =>
          BaseRorKbnRule.processUsingJwtToken(blockContext, Authorize(settings.rorKbn, groupsLogic))
        case _ =>
          RuleResult.Rejected()
      }
    }

}

object RorKbnAuthorizationRule {

  implicit case object Name extends RuleName[RorKbnAuthorizationRule] {
    override val name = Rule.Name("ror_kbn_authorization")
  }

  final case class Settings(rorKbn: RorKbnDef, groupsLogic: GroupsLogic)
}

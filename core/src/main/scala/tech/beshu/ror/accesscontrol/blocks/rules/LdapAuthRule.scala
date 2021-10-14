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

import cats.Eq
import monix.eval.Task
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule._
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.AuthenticationImpersonationSupport.UserExistence
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.AuthorizationImpersonationSupport.Groups
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.ImpersonationSettings
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality

final class LdapAuthRule(val authentication: LdapAuthenticationRule,
                         val authorization: LdapAuthorizationRule,
                         implicit override val caseMappingEquality: UserIdCaseMappingEquality)
  extends AuthRule {

  override val name: Rule.Name = LdapAuthRule.Name.name

  override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable

  override def tryToAuthenticate[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = {
    authentication
      .check(blockContext)
      .flatMap {
        case fulfilled: Fulfilled[B] =>
          authorization.check(fulfilled.blockContext)
        case Rejected(_) =>
          Task.now(Rejected())
      }
  }

  override protected val impersonationSetting: ImpersonationSettings =
    authentication.impersonationSetting

  override protected val mocksProvider: MocksProvider =
    authorization.mocksProvider

  override protected[rules] def exists(user: User.Id)
                                      (implicit requestId: RequestId,
                                       eq: Eq[User.Id]): Task[UserExistence] =
    authentication.exists(user)

  override protected[rules] def mockedGroupsOf(user: User.Id)
                                              (implicit requestId: RequestId,
                                               eqUserId: Eq[User.Id]): Groups =
    authorization.mockedGroupsOf(user)
}

object LdapAuthRule {
  implicit case object Name extends RuleName[LdapAuthRule] {
    override val name = Rule.Name("ldap_auth")
  }
}
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

import cats.implicits._
import cats.Eq
import monix.eval.Task
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.definitions.ExternalAuthenticationService
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseBasicAuthAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.SimpleAuthenticationImpersonationSupport.UserExistence
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.{Credentials, User}

final class ExternalAuthenticationRule(val settings: ExternalAuthenticationRule.Settings,
                                       override val impersonation: Impersonation,
                                       implicit override val caseMappingEquality: UserIdCaseMappingEquality)
  extends BaseBasicAuthAuthenticationRule {

  override val name: Rule.Name = ExternalAuthenticationRule.Name.name

  override val eligibleUsers: EligibleUsersSupport = EligibleUsersSupport.NotAvailable

  override protected def authenticateUsing(credentials: Credentials): Task[Boolean] =
    settings.service.authenticate(credentials)

  override protected[rules] def exists(user: User.Id, mocksProvider: MocksProvider)
                                      (implicit requestId: RequestId, eq: Eq[User.Id]): Task[UserExistence] = Task.delay {
    mocksProvider
      .externalAuthenticationServiceWith(settings.service.id)
      .map { mock =>
        val ldapUserExists = mock.users.exists(_.id === user)
        if (ldapUserExists) UserExistence.Exists
        else UserExistence.NotExist
      }
      .getOrElse {
        UserExistence.CannotCheck
      }
  }
}

object ExternalAuthenticationRule {

  implicit case object Name extends RuleName[ExternalAuthenticationRule] {
    override val name = Rule.Name("external_authentication")
  }

  final case class Settings(service: ExternalAuthenticationService)
}
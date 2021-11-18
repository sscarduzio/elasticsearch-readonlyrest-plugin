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
import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.SimpleAuthenticationImpersonationSupport.UserExistence
import tech.beshu.ror.accesscontrol.blocks.rules.base.{BasicAuthenticationRule, Rule}
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.{Credentials, User}

final class AuthKeyRule(override val settings: BasicAuthenticationRule.Settings[Credentials],
                        override val impersonation: Impersonation,
                        implicit override val caseMappingEquality: UserIdCaseMappingEquality)
  extends BasicAuthenticationRule(settings)
    with Logging {

  override val name: Rule.Name = AuthKeyRule.Name.name

  override protected def compare(configuredCredentials: Credentials,
                                 credentials: Credentials): Task[Boolean] = Task.now {
    import tech.beshu.ror.utils.CaseMappingEquality._
    configuredCredentials === credentials
  }

  override def exists(user: User.Id, mocksProvider: MocksProvider)
                     (implicit requestId: RequestId,
                      eq: Eq[User.Id]): Task[UserExistence] = Task.now {
    if (user === settings.credentials.user) UserExistence.Exists
    else UserExistence.NotExist
  }


  override val eligibleUsers: EligibleUsersSupport =
    EligibleUsersSupport.Available(Set(settings.credentials.user))
}

object AuthKeyRule {
  implicit case object Name extends RuleName[AuthKeyRule] {
    override val name = Rule.Name("auth_key")
  }
}

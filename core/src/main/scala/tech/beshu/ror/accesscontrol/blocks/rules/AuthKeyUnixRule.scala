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

import java.util.regex.Pattern

import cats.Eq
import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import org.apache.commons.codec.digest.Crypt.crypt
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.rules.AuthKeyUnixRule.UnixHashedCredentials
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleName
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.SimpleAuthenticationImpersonationSupport.UserExistence
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.blocks.rules.base.{BasicAuthenticationRule, Rule}
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.{Credentials, User}

final class AuthKeyUnixRule(override val settings: BasicAuthenticationRule.Settings[UnixHashedCredentials],
                            override val impersonation: Impersonation,
                            implicit override val caseMappingEquality: UserIdCaseMappingEquality)
  extends BasicAuthenticationRule(settings) {

  override val name: Rule.Name = AuthKeyUnixRule.Name.name

  override protected def compare(configuredCredentials: UnixHashedCredentials,
                                 credentials: Credentials): Task[Boolean] = Task {
    import tech.beshu.ror.utils.CaseMappingEquality._
    configuredCredentials.userId === credentials.user &&
      configuredCredentials.from(credentials).contains(configuredCredentials)
  }

  override def exists(user: User.Id)
                     (implicit requestId: RequestId,
                      eq: Eq[User.Id]): Task[UserExistence] = Task.now {
    if (user === settings.credentials.userId) UserExistence.Exists
    else UserExistence.NotExist
  }

  override val eligibleUsers: EligibleUsersSupport =
    EligibleUsersSupport.Available(Set(settings.credentials.userId))
}

object AuthKeyUnixRule {

  implicit case object Name extends RuleName[AuthKeyUnixRule] {
    override val name = Rule.Name("auth_key_unix")
  }

  private val pattern = Pattern.compile("((?:[^$]*\\$){3}[^$]*).*")

  final case class UnixHashedCredentials(userId: User.Id, hash: NonEmptyString) {
    def from(credentials: Credentials): Option[UnixHashedCredentials] = {
      roundHash(credentials).map(UnixHashedCredentials(credentials.user, _))
    }

    private def roundHash(credentials: Credentials): Option[NonEmptyString] = {
      val m = AuthKeyUnixRule.pattern.matcher(hash.value)
      if (m.find) NonEmptyString.unapply(crypt(credentials.secret.value.value, m.group(1)))
      else None
    }
  }

}

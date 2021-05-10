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

import cats.implicits._
import cats.kernel.Eq
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.definitions.ImpersonatorDef
import tech.beshu.ror.accesscontrol.blocks.rules.AuthKeyHashingRule.HashedCredentials
import tech.beshu.ror.accesscontrol.blocks.rules.AuthKeyHashingRule.HashedCredentials.{HashedOnlyPassword, HashedUserAndPassword}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.{EligibleUsersSupport, UserExistence}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleName
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.utils.CaseMappingEquality._
import tech.beshu.ror.utils.Hasher

abstract class AuthKeyHashingRule(settings: BasicAuthenticationRule.Settings[HashedCredentials],
                                  hasher: Hasher)
  extends BasicAuthenticationRule(settings)
    with Logging {

  override protected def compare(configuredCredentials: HashedCredentials,
                                 credentials: Credentials): Task[Boolean] = Task {
    configuredCredentials match {
      case secret: HashedUserAndPassword =>
        secret === HashedUserAndPassword.from(credentials, hasher)
      case secret: HashedOnlyPassword =>
        implicit val caseMappingEquality: UserIdCaseMappingEquality = this.caseMappingEquality
        secret === HashedOnlyPassword.from(credentials, hasher)
    }
  }

  override final def exists(user: User.Id)
                           (implicit userIdEq: Eq[User.Id]): Task[UserExistence] = Task.now {
    settings.credentials match {
      case HashedUserAndPassword(_) => UserExistence.CannotCheck
      case HashedOnlyPassword(userId, _) if userId === user => UserExistence.Exists
      case HashedOnlyPassword(_, _) => UserExistence.NotExist
    }
  }

  override val eligibleUsers: EligibleUsersSupport = settings.credentials match {
    case HashedCredentials.HashedUserAndPassword(_) => EligibleUsersSupport.NotAvailable
    case HashedCredentials.HashedOnlyPassword(userId, _) => EligibleUsersSupport.Available(Set(userId))
  }
}

object AuthKeyHashingRule {

  sealed trait HashedCredentials

  object HashedCredentials {

    final case class HashedUserAndPassword(hash: NonEmptyString) extends HashedCredentials
    object HashedUserAndPassword {
      def from(credentials: Credentials, hasher: Hasher): HashedUserAndPassword = HashedUserAndPassword {
        NonEmptyString.unsafeFrom(
          hasher.hashString(s"${credentials.user.value.value}:${credentials.secret.value}"))
      }

      implicit val eqHashedUserAndPassword: Eq[HashedUserAndPassword] = Eq.by(_.hash.value)
    }

    final case class HashedOnlyPassword(userId: User.Id, hash: NonEmptyString) extends HashedCredentials
    object HashedOnlyPassword {
      def from(credentials: Credentials, hasher: Hasher): HashedOnlyPassword = HashedOnlyPassword(
        credentials.user,
        NonEmptyString.unsafeFrom(hasher.hashString(credentials.secret.value.value))
      )

      implicit def eqHashedOnlyPassword(implicit caseMappingEquality: UserIdCaseMappingEquality): Eq[HashedOnlyPassword] =
        Eq.and(Eq.by(_.hash.value), Eq.by(_.userId))

    }
  }

}

final class AuthKeySha1Rule(override val settings: BasicAuthenticationRule.Settings[HashedCredentials],
                            override val impersonators: List[ImpersonatorDef],
                            implicit override val caseMappingEquality: UserIdCaseMappingEquality)
  extends AuthKeyHashingRule(settings, hasher = Hasher.Sha1) {

  override val name: Rule.Name = AuthKeySha1Rule.Name.name
}

object AuthKeySha1Rule {
  implicit case object Name extends RuleName[AuthKeySha1Rule] {
    override val name = Rule.Name("auth_key_sha1")
  }
}

final class AuthKeySha256Rule(settings: BasicAuthenticationRule.Settings[HashedCredentials],
                              override val impersonators: List[ImpersonatorDef],
                              implicit override val caseMappingEquality: UserIdCaseMappingEquality)
  extends AuthKeyHashingRule(settings, hasher = Hasher.Sha256) {

  override val name: Rule.Name = AuthKeySha256Rule.Name.name
}

object AuthKeySha256Rule {
  implicit case object Name extends RuleName[AuthKeySha256Rule] {
    override val name = Rule.Name("auth_key_sha256")
  }
}

final class AuthKeySha512Rule(override val settings: BasicAuthenticationRule.Settings[HashedCredentials],
                              override val impersonators: List[ImpersonatorDef],
                              implicit override val caseMappingEquality: UserIdCaseMappingEquality)
  extends AuthKeyHashingRule(settings, hasher = Hasher.Sha512) {

  override val name: Rule.Name = AuthKeySha512Rule.Name.name
}

object AuthKeySha512Rule {
  implicit case object Name extends RuleName[AuthKeySha512Rule] {
    override val name = Rule.Name("auth_key_sha512")
  }
}

final class AuthKeyPBKDF2WithHmacSHA512Rule(override val settings: BasicAuthenticationRule.Settings[HashedCredentials],
                                            override val impersonators: List[ImpersonatorDef],
                                            implicit override val caseMappingEquality: UserIdCaseMappingEquality)
  extends AuthKeyHashingRule(settings, hasher = Hasher.PBKDF2WithHmacSHA512) {

  override val name: Rule.Name = AuthKeyPBKDF2WithHmacSHA512Rule.Name.name
}

object AuthKeyPBKDF2WithHmacSHA512Rule {
  implicit case object Name extends RuleName[AuthKeyPBKDF2WithHmacSHA512Rule] {
    override val name = Rule.Name("auth_key_pbkdf2")
  }
}

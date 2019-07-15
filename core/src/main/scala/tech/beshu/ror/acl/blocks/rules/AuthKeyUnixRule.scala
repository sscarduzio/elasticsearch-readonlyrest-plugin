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
package tech.beshu.ror.acl.blocks.rules

import java.util.regex.Pattern

import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import org.apache.commons.codec.digest.Crypt.crypt
import tech.beshu.ror.acl.domain.{BasicAuth, Secret, User}

class AuthKeyUnixRule(settings: BasicAuthenticationRule.Settings)
  extends BasicAuthenticationRule(settings) {

  override val name: Rule.Name = AuthKeyUnixRule.name

  override protected def compare(configuredAuthKey: Secret,
                                 basicAuth: BasicAuth): Task[Boolean] = Task {
    configuredAuthKey.value.split(":").toList match {
      case user :: pass :: Nil =>
        NonEmptyString.from(user) match {
          case Right(userStr) if User.Id(userStr) === basicAuth.user =>
            configuredAuthKey === roundHash(pass, basicAuth)
          case Left(_) =>
            false
        }
      case _ =>
        false
    }
  }

  private def roundHash(key: String, basicAuth: BasicAuth): Secret = {
    val m = AuthKeyUnixRule.pattern.matcher(key)
    if (m.find) Secret(s"${basicAuth.user.value}:${crypt(basicAuth.secret.value, m.group(1))}")
    else Secret.empty
  }
}

object AuthKeyUnixRule {
  val name = Rule.Name("auth_key_unix")

  private val pattern = Pattern.compile("((?:[^$]*\\$){3}[^$]*).*")
}

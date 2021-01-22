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
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.definitions.ImpersonatorDef
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.UserExistence
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.{Credentials, User}
import tech.beshu.ror.utils.CaseMappingEquality._

final class AuthKeyRule(settings: BasicAuthenticationRule.Settings[Credentials],
                  override val impersonators: List[ImpersonatorDef])
                 (override implicit val caseMappingEquality: UserIdCaseMappingEquality)
  extends BasicAuthenticationRule(settings)
    with Logging {

  override val name: Rule.Name = AuthKeyRule.name

  override protected def compare(configuredCredentials: Credentials,
                                 credentials: Credentials): Task[Boolean] = Task.now {
    configuredCredentials === credentials
  }

  override def exists(user: User.Id)
                     (implicit caseMappingEquality: UserIdCaseMappingEquality): Task[UserExistence] = Task.now {
    if (user === settings.credentials.user) UserExistence.Exists
    else UserExistence.NotExist
  }
}

object AuthKeyRule {
  val name = Rule.Name("auth_key")
}

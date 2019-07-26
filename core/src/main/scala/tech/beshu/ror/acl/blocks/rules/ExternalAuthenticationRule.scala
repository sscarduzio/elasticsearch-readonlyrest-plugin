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

import monix.eval.Task
import tech.beshu.ror.acl.blocks.definitions.ExternalAuthenticationService
import tech.beshu.ror.acl.blocks.rules.Rule.ImpersonationSupport.UserExistence
import tech.beshu.ror.acl.domain.{Credentials, User}

class ExternalAuthenticationRule(val settings: ExternalAuthenticationRule.Settings)
  extends BaseBasicAuthenticationRule {

  override val name: Rule.Name = ExternalAuthenticationRule.name

  override protected def authenticate(credentials: Credentials): Task[Boolean] =
    settings.service.authenticate(credentials)

  override def exists(user: User.Id): Task[UserExistence] = Task.now(UserExistence.CannotCheck)
}

object ExternalAuthenticationRule {
  val name = Rule.Name("external_authentication")

  final case class Settings(service: ExternalAuthenticationService)
}
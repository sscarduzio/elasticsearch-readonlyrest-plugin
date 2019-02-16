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
import tech.beshu.ror.acl.aDomain.BasicAuth
import tech.beshu.ror.acl.blocks.definitions.ExternalAuthenticationService

class ExternalAuthenticationRule(val settings: ExternalAuthenticationRule.Settings)
  extends BaseBasicAuthenticationRule {

  override val name: Rule.Name = ExternalAuthenticationRule.name

  override protected def authenticate(credentials: BasicAuth): Task[Boolean] =
    settings.service.authenticate(credentials.user, credentials.secret)
}

object ExternalAuthenticationRule {
  val name = Rule.Name("external_authentication")

  final case class Settings(service: ExternalAuthenticationService)
}
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
package tech.beshu.ror.accesscontrol.response

import cats.data.NonEmptyList
import tech.beshu.ror.accesscontrol.AccessControlList.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.syntax

object RorKbnPluginNotSupported {

  // This is a response context for user metadata endpoint, when 'prompt_for_basic_auth: true' setting is configured.
  // That setting means, that ROR ES plugin will return HTTP status 401 and will request user to provide basic auth credentials.
  // This response context dedicated for user metadata endpoint:
  // - has custom error message
  // - sets doesRequirePassword=false, which means that the response will be returned with HTTP 403 Forbidden status
  def forbiddenResponseContext(aclStaticContext: AccessControlStaticContext): ForbiddenResponseContext =
    new ForbiddenResponseContext(
      aclStaticContext = Some(new UserMetadataAccessControlStaticContext(aclStaticContext)),
      forbiddenCauses = NonEmptyList.one(ForbiddenResponseContext.OperationNotAllowed)
    )

  val message = "The ES ROR is configured with 'prompt_for_basic_auth: true' setting. This setting is appropriate only for using Kibana without KBN ROR plugin. See our docs for details https://docs.readonlyrest.com/elasticsearch#prompt_for_basic_auth"

  private class UserMetadataAccessControlStaticContext(underlying: AccessControlStaticContext)
    extends AccessControlStaticContext {

    override def usedFlsEngineInFieldsRule: Option[GlobalSettings.FlsEngine] = underlying.usedFlsEngineInFieldsRule

    override def obfuscatedHeaders: syntax.Set[Header.Name] = underlying.obfuscatedHeaders

    override def doesRequirePassword: Boolean = false

    override def forbiddenRequestMessage: String = message

  }

}

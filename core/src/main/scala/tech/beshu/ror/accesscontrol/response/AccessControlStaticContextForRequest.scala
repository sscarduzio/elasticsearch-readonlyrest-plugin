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

import tech.beshu.ror.accesscontrol.AccessControlList.AccessControlStaticContext
import tech.beshu.ror.accesscontrol.domain.{Header, RorKbnLicenseType}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.request.RestRequest
import tech.beshu.ror.syntax

object AccessControlStaticContextForRequest {

  /**
   * The static context as one request sees it.
   *
   * The ROR Kibana plugin marks each request it sends with the license type header. It runs its own
   * login, which the basic auth prompt of the browser breaks, so ROR never asks that request for
   * credentials. Every other client keeps the behaviour of the `prompt_for_basic_auth` setting.
   */
  def apply(staticContext: AccessControlStaticContext, restRequest: RestRequest): AccessControlStaticContext = {
    if (sentByRorKbnPlugin(restRequest)) new NoBasicAuthPrompt(staticContext)
    else staticContext
  }

  private def sentByRorKbnPlugin(restRequest: RestRequest): Boolean = {
    restRequest.allHeaders
      .find(_.name == Header.Name.rorKbnLicenseType)
      .exists(header => RorKbnLicenseType.from(header.value.value).isRight)
  }

  private class NoBasicAuthPrompt(underlying: AccessControlStaticContext) extends AccessControlStaticContext {

    override def usedFlsEngineInFieldsRule: Option[GlobalSettings.FlsEngine] = underlying.usedFlsEngineInFieldsRule

    override def obfuscatedHeaders: syntax.Set[Header.Name] = underlying.obfuscatedHeaders

    override def forbiddenRequestMessage: String = underlying.forbiddenRequestMessage

    override def doesRequirePassword: Boolean = false

  }

}

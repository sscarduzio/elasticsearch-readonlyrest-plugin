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
import tech.beshu.ror.accesscontrol.request.RequestContext

object BasicAuthPrompt {

  /**
   * Does ROR ask this request for basic auth credentials?
   *
   * The `prompt_for_basic_auth` setting turns the prompt on for every client. The ROR Kibana plugin
   * is the exception: it runs its own login, which the prompt of the browser breaks.
   */
  def isEnabledFor(requestContext: RequestContext, aclStaticContext: AccessControlStaticContext): Boolean =
    aclStaticContext.doesRequirePassword && requestContext.rorKbnLicenseType.isEmpty

}

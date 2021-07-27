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
package tech.beshu.ror.accesscontrol

import monix.eval.Task
import tech.beshu.ror.accesscontrol.AccessControl.{AccessControlStaticContext, RegularRequestResult, UserMetadataRequestResult, WithHistory}
import tech.beshu.ror.accesscontrol.blocks.BlockContext.CurrentUserMetadataRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.request.RequestContext.Aux

object DisabledAccessControl extends AccessControl {

  override def handleRegularRequest[B <: BlockContext : BlockContextUpdater](requestContext: Aux[B]): Task[WithHistory[RegularRequestResult[B], B]] =
    Task.now(WithHistory.withNoHistory(RegularRequestResult.PassedThrough()))

  override def handleMetadataRequest(requestContext: Aux[CurrentUserMetadataRequestBlockContext]): Task[WithHistory[UserMetadataRequestResult, CurrentUserMetadataRequestBlockContext]] =
    Task.now(WithHistory.withNoHistory(UserMetadataRequestResult.PassedThrough))

  override val staticContext: AccessControl.AccessControlStaticContext = new AccessControlStaticContext {
    override val usedFlsEngineInFieldsRule: Option[GlobalSettings.FlsEngine] = None
    override val doesRequirePassword: Boolean = false
    override val forbiddenRequestMessage: String = ""
    override val obfuscatedHeaders: Set[Header.Name] = Set.empty
  }
}

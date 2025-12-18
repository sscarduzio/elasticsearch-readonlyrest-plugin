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
package tech.beshu.ror.accesscontrol.factory.decoders.rules.http

import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.rules.http.SessionMaxIdleRule
import tech.beshu.ror.accesscontrol.blocks.rules.http.SessionMaxIdleRule.Settings
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.utils.CirceOps.*
import tech.beshu.ror.providers.UuidProvider

import java.time.Clock

class SessionMaxIdleRuleDecoder(globalSettings: GlobalSettings)
                               (implicit clock: Clock,
                                uuidProvider: UuidProvider)
  extends RuleBaseDecoderWithoutAssociatedFields[SessionMaxIdleRule] {

  override protected def decoder: Decoder[RuleDefinition[SessionMaxIdleRule]] = {
    common
      .positiveFiniteDurationDecoder
      .map(maxIdle => RuleDefinition.create(new SessionMaxIdleRule(Settings(maxIdle), globalSettings.userIdCaseSensitivity)))
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .decoder
  }
}

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
package tech.beshu.ror.accesscontrol.factory.decoders.rules

import cats.Eq
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleWithVariableUsageDefinition
import tech.beshu.ror.accesscontrol.blocks.rules.SessionMaxIdleRule
import tech.beshu.ror.accesscontrol.blocks.rules.SessionMaxIdleRule.Settings
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.utils.CirceOps._
import tech.beshu.ror.providers.UuidProvider

import java.time.Clock

class SessionMaxIdleRuleDecoder(implicit clock: Clock,
                                uuidProvider: UuidProvider,
                                userIdEq: Eq[User.Id])
  extends RuleDecoderWithoutAssociatedFields(
    common
      .positiveFiniteDurationDecoder
      .map(maxIdle => RuleWithVariableUsageDefinition.create(new SessionMaxIdleRule(Settings(maxIdle))))
      .toSyncDecoder
      .mapError(RulesLevelCreationError.apply)
      .decoder
  )

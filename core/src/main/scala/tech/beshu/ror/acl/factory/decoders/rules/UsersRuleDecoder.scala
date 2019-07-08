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
package tech.beshu.ror.acl.factory.decoders.rules

import io.circe.Decoder
import tech.beshu.ror.acl.blocks.rules.UsersRule
import tech.beshu.ror.acl.blocks.rules.UsersRule.Settings
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.acl.domain.User
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.factory.decoders.rules.UsersRuleDecoderHelper.userIdValueDecoder
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.providers.EnvVarsProvider

class UsersRuleDecoder(implicit provider: EnvVarsProvider) extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers
    .decodeStringLikeOrNonEmptySet[RuntimeMultiResolvableVariable[User.Id]]
    .map(users => new UsersRule(Settings(users)))
)

private object UsersRuleDecoderHelper {
  implicit val userIdValueDecoder: Decoder[RuntimeMultiResolvableVariable[User.Id]] =
    DecoderHelpers.alwaysRightMultiVariableDecoder[User.Id](AlwaysRightConvertible.from(User.Id.apply))
}

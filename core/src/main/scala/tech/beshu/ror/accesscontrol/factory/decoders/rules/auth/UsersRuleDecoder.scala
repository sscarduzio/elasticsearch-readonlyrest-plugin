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
package tech.beshu.ror.accesscontrol.factory.decoders.rules.auth

import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.rules.auth.UsersRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.UsersRule.Settings
import tech.beshu.ror.accesscontrol.blocks.variables.VariableCreationConfig
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariable.Convertible.AlwaysRightConvertible
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.factory.decoders.rules.auth.UsersRuleDecoderHelper.userIdValueDecoder
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.utils.CaseMappingEquality._

class UsersRuleDecoder(implicit val caseMappingEquality: UserIdCaseMappingEquality,
                       implicit val variableCreationConfig: VariableCreationConfig)
  extends RuleBaseDecoderWithoutAssociatedFields[UsersRule] {

  override protected def decoder: Decoder[RuleDefinition[UsersRule]] = {
    DecoderHelpers
      .decodeStringLikeOrNonEmptySet[RuntimeMultiResolvableVariable[User.Id]]
      .map(users => RuleDefinition.create(new UsersRule(Settings(users), caseMappingEquality)))
  }
}

private object UsersRuleDecoderHelper {
  implicit def userIdValueDecoder(implicit variableCreationConfig: VariableCreationConfig): Decoder[RuntimeMultiResolvableVariable[User.Id]] =
    DecoderHelpers.alwaysRightMultiVariableDecoder[User.Id](variableCreationConfig)(AlwaysRightConvertible.from(User.Id.apply))
}

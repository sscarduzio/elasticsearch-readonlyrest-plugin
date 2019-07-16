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
import tech.beshu.ror.acl.blocks.rules._
import tech.beshu.ror.acl.blocks.rules.impersonation.ImpersonationRuleDecorator
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.acl.domain.Secret

object AuthKeyRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  AuthKeyDecodersHelper
    .basicAuthenticationRuleSettingsDecoder
    .map(new AuthKeyRule(_))
    .map(new ImpersonationRuleDecorator(_))
)

object AuthKeySha1RuleDecoder extends RuleDecoderWithoutAssociatedFields(
  AuthKeyDecodersHelper
    .basicAuthenticationRuleSettingsDecoder
    .map(new AuthKeySha1Rule(_))
    .map(new ImpersonationRuleDecorator(_))
)

object AuthKeySha256RuleDecoder extends RuleDecoderWithoutAssociatedFields(
  AuthKeyDecodersHelper
    .basicAuthenticationRuleSettingsDecoder
    .map(new AuthKeySha256Rule(_))
    .map(new ImpersonationRuleDecorator(_))
)

object AuthKeySha512RuleDecoder extends RuleDecoderWithoutAssociatedFields(
  AuthKeyDecodersHelper
    .basicAuthenticationRuleSettingsDecoder
    .map(new AuthKeySha512Rule(_))
    .map(new ImpersonationRuleDecorator(_))
)

object AuthKeyUnixRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  AuthKeyDecodersHelper
    .basicAuthenticationRuleSettingsDecoder
    .map(new AuthKeyUnixRule(_))
    .map(new ImpersonationRuleDecorator(_))
)

private object AuthKeyDecodersHelper {
  val basicAuthenticationRuleSettingsDecoder: Decoder[BasicAuthenticationRule.Settings] =
    DecoderHelpers.decodeStringLike.map(Secret.apply).map(BasicAuthenticationRule.Settings.apply)
}
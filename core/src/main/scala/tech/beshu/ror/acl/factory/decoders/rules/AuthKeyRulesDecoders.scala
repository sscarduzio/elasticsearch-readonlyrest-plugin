package tech.beshu.ror.acl.factory.decoders.rules

import io.circe.Decoder
import tech.beshu.ror.acl.blocks.rules._
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.acl.aDomain.Secret

object AuthKeyRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  AuthKeyDecodersHelper.basicAuthenticationRuleSettingsDecoder.map(new AuthKeyRule(_))
)

object AuthKeySha1RuleDecoder extends RuleDecoderWithoutAssociatedFields(
  AuthKeyDecodersHelper.basicAuthenticationRuleSettingsDecoder.map(new AuthKeySha1Rule(_))
)

object AuthKeySha256RuleDecoder extends RuleDecoderWithoutAssociatedFields(
  AuthKeyDecodersHelper.basicAuthenticationRuleSettingsDecoder.map(new AuthKeySha256Rule(_))
)

object AuthKeySha512RuleDecoder extends RuleDecoderWithoutAssociatedFields(
  AuthKeyDecodersHelper.basicAuthenticationRuleSettingsDecoder.map(new AuthKeySha512Rule(_))
)

object AuthKeyUnixRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  AuthKeyDecodersHelper.basicAuthenticationRuleSettingsDecoder.map(new AuthKeyUnixRule(_))
)

private object AuthKeyDecodersHelper {
  val basicAuthenticationRuleSettingsDecoder: Decoder[BasicAuthenticationRule.Settings] =
    DecoderHelpers.decodeStringLike.map(Secret.apply).map(BasicAuthenticationRule.Settings.apply)
}
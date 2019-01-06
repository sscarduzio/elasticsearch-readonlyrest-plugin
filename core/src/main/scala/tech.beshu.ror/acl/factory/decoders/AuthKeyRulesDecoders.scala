package tech.beshu.ror.acl.factory.decoders

import io.circe.Decoder
import tech.beshu.ror.acl.blocks.rules._
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers

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

// todo: implement
//object ProxyAuthRuleDecoder extends

private object AuthKeyDecodersHelper {
  val basicAuthenticationRuleSettingsDecoder: Decoder[BasicAuthenticationRule.Settings] =
    DecoderHelpers.decodeStringLike.map(BasicAuthenticationRule.Settings.apply)
}
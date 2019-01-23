package tech.beshu.ror.acl.factory.decoders.rules

import cats.data.NonEmptySet
import cats.implicits._
import io.circe.Decoder
import tech.beshu.ror.acl.blocks.Value
import tech.beshu.ror.acl.blocks.rules.{HostsRule, LocalHostsRule}
import tech.beshu.ror.acl.factory.decoders.rules.HostRulesDecodersHelper._
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.acl.aDomain.Address
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.{RuleDecoderWithAssociatedFields, RuleDecoderWithoutAssociatedFields}
import tech.beshu.ror.acl.orders._

object HostsRuleDecoder extends RuleDecoderWithAssociatedFields[HostsRule, Boolean](
  ruleDecoderCreator = acceptXForwardedFor =>
    DecoderHelpers
      .decodeStringLikeOrNonEmptySet[Value[Address]]
      .map(nes => new HostsRule(HostsRule.Settings(nes, acceptXForwardedFor))),
  associatedFields = NonEmptySet.one("accept_x-forwarded-for_header"),
  associatedFieldsDecoder =
    Decoder.instance(_.downField("accept_x-forwarded-for_header").as[Boolean])
      .or(Decoder.const(defaultAcceptForwardedForHeader))
)

object LocalHostsRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers
    .decodeStringLikeOrNonEmptySet[Value[Address]]
    .map(addresses => new LocalHostsRule(LocalHostsRule.Settings(addresses)))
)

private object HostRulesDecodersHelper {

  val defaultAcceptForwardedForHeader = false

  implicit val addressValueDecoder: Decoder[Value[Address]] =
    DecoderHelpers.alwaysRightValueDecoder(rv => Address(rv.value))
}
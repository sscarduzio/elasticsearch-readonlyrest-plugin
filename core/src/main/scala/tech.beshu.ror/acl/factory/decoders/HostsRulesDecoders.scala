package tech.beshu.ror.acl.factory.decoders

import cats.data.NonEmptySet
import cats.implicits._
import io.circe.Decoder
import tech.beshu.ror.acl.blocks.Value
import tech.beshu.ror.acl.blocks.rules.{HostsRule, LocalHostsRule}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.HostRulesDecodersHelper._
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.RuleDecoder.{RuleDecoderWithAssociatedFields, RuleDecoderWithoutAssociatedFields}
import tech.beshu.ror.acl.utils.CirceOps.{DecoderHelpers, DecodingFailureOps}
import tech.beshu.ror.commons.aDomain.Address
import tech.beshu.ror.commons.orders._

import scala.collection.immutable.SortedSet

object HostsRuleDecoder extends RuleDecoderWithAssociatedFields[HostsRule, Boolean](
  ruleDecoderCreator = acceptXForwardedFor =>
    Decoder
      .decodeList[Value[Address]]
      .map(v => SortedSet(v: _*))
      .map(NonEmptySet.fromSet)
      .flatMap {
        case Some(nes) =>
          Decoder.const(new HostsRule(HostsRule.Settings(nes, acceptXForwardedFor)))
        case None =>
          Decoder.failed(DecodingFailureOps.fromError(RulesLevelCreationError(s"At least one host in ${HostsRule.name} rule should be defined")))
      },
  associatedFields = NonEmptySet.one("accept_x-forwarded-for_header"),
  associatedFieldsDecoder = Decoder.instance(_.downField("accept_x-forwarded-for_header").as[Boolean])
)

object LocalHostsRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers
    .decodeStringLikeOrNonEmptySet[Value[Address]]
    .map(addresses => new LocalHostsRule(LocalHostsRule.Settings(addresses)))
)

object HostRulesDecodersHelper {

  implicit val addressValueDecoder: Decoder[Value[Address]] = DecoderHelpers.valueDecoder(rv => Address(rv.value))
}
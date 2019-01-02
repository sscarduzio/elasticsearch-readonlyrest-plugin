package tech.beshu.ror.acl.factory.decoders

import cats.implicits._
import cats.data.NonEmptySet
import io.circe.Decoder
import tech.beshu.ror.acl.blocks.Value
import tech.beshu.ror.acl.blocks.Variable.ResolvedValue
import tech.beshu.ror.acl.blocks.rules.HostsRule
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.RuleDecoder.RuleDecoderWithAssociatedFields
import tech.beshu.ror.acl.utils.CirceOps.{DecoderOps, DecodingFailureOps}
import tech.beshu.ror.commons.aDomain.Address
import tech.beshu.ror.commons.orders._

import scala.collection.immutable.SortedSet
import HostRuleDecoderHelper._

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

// todo: move somewhere else
object HostRuleDecoderHelper {

  private def valueDecoder[T](convert: ResolvedValue => T): Decoder[Value[T]] =
    DecoderOps.decodeStringOrNumber.map(str => Value.fromString(str, convert))

  implicit val addressValueDecoder: Decoder[Value[Address]] = valueDecoder(rv => Address(rv.value))
}
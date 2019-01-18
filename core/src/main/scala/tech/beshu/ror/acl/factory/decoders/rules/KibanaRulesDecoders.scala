package tech.beshu.ror.acl.factory.decoders.rules

import cats.data.NonEmptySet
import cats.implicits._
import io.circe.Decoder
import tech.beshu.ror.acl.blocks.rules.KibanaHideAppsRule.Settings
import tech.beshu.ror.acl.blocks.rules.{KibanaAccessRule, KibanaHideAppsRule, KibanaIndexRule}
import tech.beshu.ror.acl.blocks.{Const, Value}
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.ruleDecoders.RuleDecoder.{RuleDecoderWithAssociatedFields, RuleDecoderWithoutAssociatedFields}
import tech.beshu.ror.acl.factory.decoders.rules.KibanaRulesDecoderHelper._
import tech.beshu.ror.acl.utils.CirceOps._
import tech.beshu.ror.acl.aDomain.{IndexName, KibanaAccess, KibanaApp}
import tech.beshu.ror.acl.orders._

import scala.util.Try

object KibanaHideAppsRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers.decodeStringLikeOrNonEmptySet(KibanaApp.apply).map(apps => new KibanaHideAppsRule(Settings(apps)))
)

object KibanaIndexRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  KibanaRulesDecoderHelper
    .kibanaIndexDecoder
    .map { index =>
      new KibanaIndexRule(KibanaIndexRule.Settings(index))
    }
)

// todo: at the moment kibana_index must be defined after kibana_access. We should allow to place it anywhere
object KibanaAccessRuleDecoder extends RuleDecoderWithAssociatedFields[KibanaAccessRule, Value[IndexName]](
  ruleDecoderCreator = kibanaIndexName =>
    DecoderHelpers
      .decodeStringLike
      .map(_.toLowerCase)
      .emapE[KibanaAccess] {
      case "ro" => Right(KibanaAccess.RO)
      case "rw" => Right(KibanaAccess.RW)
      case "ro_strict" => Right(KibanaAccess.ROStrict)
      case "admin" => Right(KibanaAccess.Admin)
      case unknown => Left(AclCreationError.RulesLevelCreationError(Message(s"Unknown kibana access '$unknown'")))
    }
      .map(KibanaAccessRule.Settings(_, kibanaIndexName, KibanaRulesDecoderHelper.readRorMetadataFlag))
      .map(s => new KibanaAccessRule(s)),
  associatedFields = NonEmptySet.of("kibana_index"),
  associatedFieldsDecoder =
    Decoder.instance(_.downField("kibana_index").as[Value[IndexName]]) or Decoder.const(Const(IndexName.kibana))
)

private object KibanaRulesDecoderHelper {
  def readRorMetadataFlag: Boolean =
    Try(System.getProperty("com.readonlyrest.kibana.metadata"))
      .map(!"false".equalsIgnoreCase(_))
      .getOrElse(true)

  implicit val kibanaIndexDecoder: Decoder[Value[IndexName]] =
    DecoderHelpers
      .decodeStringLike
      .map(e => Value.fromString(e, rv => Right(IndexName(rv.value))))
      .emapE {
        case Right(index) => Right(index)
        case Left(error) => Left(RulesLevelCreationError(Message(error.msg)))
      }
}


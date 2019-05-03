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

import cats.data.NonEmptySet
import cats.implicits._
import io.circe.Decoder
import tech.beshu.ror.acl.blocks.rules.KibanaHideAppsRule.Settings
import tech.beshu.ror.acl.blocks.rules.{KibanaAccessRule, KibanaHideAppsRule, KibanaIndexRule}
import tech.beshu.ror.acl.blocks.{Const, Value}
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.CoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.rules.KibanaRulesDecoderHelper._
import tech.beshu.ror.acl.utils.CirceOps._
import tech.beshu.ror.acl.domain.{IndexName, KibanaAccess, KibanaApp}
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.{RuleDecoderWithAssociatedFields, RuleDecoderWithoutAssociatedFields}
import tech.beshu.ror.acl.orders._

import scala.util.Try

object KibanaHideAppsRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers
    .decodeNonEmptyStringLikeOrNonEmptySet(KibanaApp.apply)
    .map(apps => new KibanaHideAppsRule(Settings(apps)))
)

object KibanaIndexRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  KibanaRulesDecoderHelper
    .kibanaIndexDecoder
    .map { index =>
      new KibanaIndexRule(KibanaIndexRule.Settings(index))
    }
)

object KibanaAccessRuleDecoder extends RuleDecoderWithAssociatedFields[KibanaAccessRule, Value[IndexName]](
  ruleDecoderCreator = kibanaIndexName =>
    DecoderHelpers
      .decodeStringLike
      .map(_.toLowerCase)
      .toSyncDecoder
      .emapE[KibanaAccess] {
      case "ro" => Right(KibanaAccess.RO)
      case "rw" => Right(KibanaAccess.RW)
      case "ro_strict" => Right(KibanaAccess.ROStrict)
      case "admin" => Right(KibanaAccess.Admin)
      case unknown => Left(AclCreationError.RulesLevelCreationError(Message(s"Unknown kibana access '$unknown'")))
    }
      .map(KibanaAccessRule.Settings(_, kibanaIndexName, KibanaRulesDecoderHelper.readRorMetadataFlag))
      .map(s => new KibanaAccessRule(s))
      .decoder,
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
      .map(e => Value.fromString(e, rv => Right(IndexName(rv.value.replace(" ", "_")))))
      .toSyncDecoder
      .emapE {
        case Right(index) => Right(index)
        case Left(error) => Left(RulesLevelCreationError(Message(error.msg)))
      }
      .decoder
}


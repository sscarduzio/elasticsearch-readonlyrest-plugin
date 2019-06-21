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
import tech.beshu.ror.acl.blocks.variables.{AlreadyResolved, RuntimeResolvableVariable, RuntimeResolvableVariableCreator}
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.rules.KibanaRulesDecoderHelper._
import tech.beshu.ror.acl.utils.CirceOps._
import tech.beshu.ror.acl.domain.{IndexName, KibanaAccess, KibanaApp}
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.{RuleDecoderWithAssociatedFields, RuleDecoderWithoutAssociatedFields}
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.utils.EnvVarsProvider

import scala.util.Try

object KibanaHideAppsRuleDecoder extends RuleDecoderWithoutAssociatedFields(
  DecoderHelpers
    .decodeNonEmptyStringLikeOrNonEmptySet(KibanaApp.apply)
    .map(apps => new KibanaHideAppsRule(Settings(apps)))
)

class KibanaIndexRuleDecoder(implicit provider: EnvVarsProvider) extends RuleDecoderWithoutAssociatedFields(
  KibanaRulesDecoderHelper
    .kibanaIndexDecoder
    .map { index =>
      new KibanaIndexRule(KibanaIndexRule.Settings(index))
    }
)

class KibanaAccessRuleDecoder(implicit provider: EnvVarsProvider)
  extends RuleDecoderWithAssociatedFields[KibanaAccessRule, RuntimeResolvableVariable[IndexName]](
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
    Decoder.instance(_.downField("kibana_index").as[RuntimeResolvableVariable[IndexName]]) or Decoder.const(AlreadyResolved(IndexName.kibana))
)

private object KibanaRulesDecoderHelper {
  // fixme: system property here?
  def readRorMetadataFlag: Boolean =
    Try(System.getProperty("com.readonlyrest.kibana.metadata"))
      .map(!"false".equalsIgnoreCase(_))
      .getOrElse(true)

  implicit def kibanaIndexDecoder(implicit provider: EnvVarsProvider): Decoder[RuntimeResolvableVariable[IndexName]] =
    DecoderHelpers
      .decodeStringLike
      .map(str => RuntimeResolvableVariableCreator.createFrom(str, extracted => Right(IndexName(extracted.replace(" ", "_")))))
      .toSyncDecoder
      .emapE {
        case Right(index) => Right(index)
        case Left(error) => Left(RulesLevelCreationError(Message(error.msg)))
      }
      .decoder
}


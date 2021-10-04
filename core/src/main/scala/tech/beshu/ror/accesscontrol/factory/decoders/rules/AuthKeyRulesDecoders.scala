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
package tech.beshu.ror.accesscontrol.factory.decoders.rules

import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.definitions.ImpersonatorDef
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.AuthKeyUnixRule.UnixHashedCredentials
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleWithVariableUsageDefinition
import tech.beshu.ror.accesscontrol.blocks.rules._
import tech.beshu.ror.accesscontrol.domain.User.Id.UserIdCaseMappingEquality
import tech.beshu.ror.accesscontrol.domain.{Credentials, PlainTextSecret, User}
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.utils.CirceOps._
import tech.beshu.ror.utils.StringWiseSplitter
import tech.beshu.ror.utils.StringWiseSplitter._

class AuthKeyRuleDecoder(impersonatorsDef: Option[Definitions[ImpersonatorDef]],
                         mocksProvider: MocksProvider,
                         implicit val caseMappingEquality: UserIdCaseMappingEquality)
  extends RuleBaseDecoderWithoutAssociatedFields[AuthKeyRule] {

  override protected def decoder: Decoder[RuleWithVariableUsageDefinition[AuthKeyRule]] = {
    AuthKeyDecodersHelper
      .plainTextCredentialsDecoder
      .map(settings =>
        RuleWithVariableUsageDefinition.create(
          new AuthKeyRule(
            settings,
            impersonatorsDef.toImpersonationSettings(mocksProvider),
            caseMappingEquality
          )
        )
      )
  }
}

class AuthKeySha1RuleDecoder(impersonatorsDef: Option[Definitions[ImpersonatorDef]],
                             mocksProvider: MocksProvider,
                             implicit val caseMappingEquality: UserIdCaseMappingEquality)
  extends RuleBaseDecoderWithoutAssociatedFields[AuthKeySha1Rule] {

  override protected def decoder: Decoder[RuleWithVariableUsageDefinition[AuthKeySha1Rule]] = {
    AuthKeyDecodersHelper
      .hashedCredentialsDecoder
      .map(settings =>
        RuleWithVariableUsageDefinition.create(
          new AuthKeySha1Rule(
            settings,
            impersonatorsDef.toImpersonationSettings(mocksProvider),
            caseMappingEquality
          )
        )
      )
  }
}

class AuthKeySha256RuleDecoder(impersonatorsDef: Option[Definitions[ImpersonatorDef]],
                               mocksProvider: MocksProvider,
                               implicit val caseMappingEquality: UserIdCaseMappingEquality)
  extends RuleBaseDecoderWithoutAssociatedFields[AuthKeySha256Rule] {

  override protected def decoder: Decoder[RuleWithVariableUsageDefinition[AuthKeySha256Rule]] = {
    AuthKeyDecodersHelper
      .hashedCredentialsDecoder
      .map(settings =>
        RuleWithVariableUsageDefinition.create(
          new AuthKeySha256Rule(
            settings,
            impersonatorsDef.toImpersonationSettings(mocksProvider),
            caseMappingEquality
          )
        )
      )
  }
}

class AuthKeySha512RuleDecoder(impersonatorsDef: Option[Definitions[ImpersonatorDef]],
                               mocksProvider: MocksProvider,
                               implicit val caseMappingEquality: UserIdCaseMappingEquality)
  extends RuleBaseDecoderWithoutAssociatedFields[AuthKeySha512Rule] {

  override protected def decoder: Decoder[RuleWithVariableUsageDefinition[AuthKeySha512Rule]] = {
    AuthKeyDecodersHelper
      .hashedCredentialsDecoder
      .map(settings =>
        RuleWithVariableUsageDefinition.create(
          new AuthKeySha512Rule(
            settings,
            impersonatorsDef.toImpersonationSettings(mocksProvider),
            caseMappingEquality
          )
        )
      )
  }
}

class AuthKeyPBKDF2WithHmacSHA512RuleDecoder(impersonatorsDef: Option[Definitions[ImpersonatorDef]],
                                             mocksProvider: MocksProvider,
                                             implicit val caseMappingEquality: UserIdCaseMappingEquality)
  extends RuleBaseDecoderWithoutAssociatedFields[AuthKeyPBKDF2WithHmacSHA512Rule] {

  override protected def decoder: Decoder[RuleWithVariableUsageDefinition[AuthKeyPBKDF2WithHmacSHA512Rule]] = {
    AuthKeyDecodersHelper
      .hashedCredentialsDecoder
      .map(settings =>
        RuleWithVariableUsageDefinition.create(
          new AuthKeyPBKDF2WithHmacSHA512Rule(
            settings,
            impersonatorsDef.toImpersonationSettings(mocksProvider),
            caseMappingEquality
          )
        )
      )
  }
}

class AuthKeyUnixRuleDecoder(impersonatorsDef: Option[Definitions[ImpersonatorDef]],
                             mocksProvider: MocksProvider,
                             implicit val caseMappingEquality: UserIdCaseMappingEquality)
  extends RuleBaseDecoderWithoutAssociatedFields[AuthKeyUnixRule] {

  override protected def decoder: Decoder[RuleWithVariableUsageDefinition[AuthKeyUnixRule]] = {
    AuthKeyDecodersHelper
      .unixHashedCredentialsDecoder
      .map(settings =>
        RuleWithVariableUsageDefinition.create(
          new AuthKeyUnixRule(
            settings,
            impersonatorsDef.toImpersonationSettings(mocksProvider),
            caseMappingEquality
          )
        )
      )
  }
}

private object AuthKeyDecodersHelper {
  val hashedCredentialsDecoder: Decoder[BasicAuthenticationRule.Settings[AuthKeyHashingRule.HashedCredentials]] =
    DecoderHelpers
      .decodeStringLikeNonEmpty
      .toSyncDecoder
      .emapE { str =>
        str.value.toNonEmptyStringsTuple match {
          case Right((first, second)) =>
            Right(AuthKeyHashingRule.HashedCredentials.HashedOnlyPassword(User.Id(first), second))
          case Left(StringWiseSplitter.Error.CannotSplitUsingColon) =>
            Right(AuthKeyHashingRule.HashedCredentials.HashedUserAndPassword(str))
          case Left(StringWiseSplitter.Error.TupleMemberCannotBeEmpty) =>
            Left(RulesLevelCreationError(Message(s"Auth key rule credentials malformed (expected two non-empty values separated with colon)")))
        }
      }
      .map(identity[AuthKeyHashingRule.HashedCredentials])
      .map(BasicAuthenticationRule.Settings.apply)
      .decoder

  val plainTextCredentialsDecoder: Decoder[BasicAuthenticationRule.Settings[Credentials]] =
    twoColonSeparatedNonEmptyStringsDecoder("Credentials")
      .map { case (first, second) =>
        BasicAuthenticationRule.Settings(Credentials(User.Id(first), PlainTextSecret(second)))
      }

  val unixHashedCredentialsDecoder: Decoder[BasicAuthenticationRule.Settings[UnixHashedCredentials]] =
    twoColonSeparatedNonEmptyStringsDecoder("Unix credentials")
      .map { case (first, second) =>
        BasicAuthenticationRule.Settings(UnixHashedCredentials(User.Id(first), second))
      }

  private def twoColonSeparatedNonEmptyStringsDecoder(fieldNameForMessage: String): Decoder[(NonEmptyString, NonEmptyString)] =
    DecoderHelpers
      .decodeStringLike
      .toSyncDecoder
      .emapE {
        _.value
          .toNonEmptyStringsTuple
          .left.map(_ => RulesLevelCreationError(Message(s"$fieldNameForMessage malformed (expected two non-empty values separated with colon)")))
      }
      .decoder

}
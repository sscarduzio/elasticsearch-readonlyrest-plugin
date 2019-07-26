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

import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import tech.beshu.ror.acl.blocks.definitions.ImpersonatorDef
import tech.beshu.ror.acl.blocks.rules.AuthKeyUnixRule.UnixHashedCredentials
import tech.beshu.ror.acl.blocks.rules.Rule.AuthenticationRule
import tech.beshu.ror.acl.blocks.rules._
import tech.beshu.ror.acl.blocks.rules.Rule.ImpersonationSupport
import tech.beshu.ror.acl.domain.{Credentials, PlainTextSecret, User}
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.RulesLevelCreationError
import tech.beshu.ror.acl.factory.decoders.definitions.Definitions
import tech.beshu.ror.acl.factory.decoders.rules.AuthKeyDecodersHelper.optionallyDecorateWithImpersonation
import tech.beshu.ror.acl.factory.decoders.rules.RuleBaseDecoder.RuleDecoderWithoutAssociatedFields
import tech.beshu.ror.acl.utils.CirceOps._
import tech.beshu.ror.utils.StringWiseSplitter
import tech.beshu.ror.utils.StringWiseSplitter._

class AuthKeyRuleDecoder(impersonatorDefs: Option[Definitions[ImpersonatorDef]]) extends RuleDecoderWithoutAssociatedFields(
  AuthKeyDecodersHelper
    .plainTextCredentialsDecoder
    .map(new AuthKeyRule(_))
    .map(optionallyDecorateWithImpersonation(impersonatorDefs, _))
)

class AuthKeySha1RuleDecoder(impersonatorDefs: Option[Definitions[ImpersonatorDef]]) extends RuleDecoderWithoutAssociatedFields(
  AuthKeyDecodersHelper
    .hashedCredentialsDecoder
    .map(new AuthKeySha1Rule(_))
    .map(optionallyDecorateWithImpersonation(impersonatorDefs, _))
)

class AuthKeySha256RuleDecoder(impersonatorDefs: Option[Definitions[ImpersonatorDef]]) extends RuleDecoderWithoutAssociatedFields(
  AuthKeyDecodersHelper
    .hashedCredentialsDecoder
    .map(new AuthKeySha256Rule(_))
    .map(optionallyDecorateWithImpersonation(impersonatorDefs, _))
)

class AuthKeySha512RuleDecoder(impersonatorDefs: Option[Definitions[ImpersonatorDef]]) extends RuleDecoderWithoutAssociatedFields(
  AuthKeyDecodersHelper
    .hashedCredentialsDecoder
    .map(new AuthKeySha512Rule(_))
    .map(optionallyDecorateWithImpersonation(impersonatorDefs, _))
)

class AuthKeyUnixRuleDecoder(impersonatorDefs: Option[Definitions[ImpersonatorDef]]) extends RuleDecoderWithoutAssociatedFields(
  AuthKeyDecodersHelper
    .unixHashedCredentialsDecoder
    .map(new AuthKeyUnixRule(_))
    .map(optionallyDecorateWithImpersonation(impersonatorDefs, _))
)

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
            Left(RulesLevelCreationError(Message(s"SHA credentials malformed (expected two non-empty values separated with colon)")))
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

  private [rules] def optionallyDecorateWithImpersonation(impersonatorDefs: Option[Definitions[ImpersonatorDef]],
                                                  rule: AuthenticationRule with ImpersonationSupport) = {
    impersonatorDefs match {
      case Some(defs) => new ImpersonationRuleDecorator(rule, defs.items)
      case None => rule
    }
  }
}
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
package tech.beshu.ror.accesscontrol.factory.decoders.rules.auth

import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.definitions.ImpersonatorDef
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.auth.TokenAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.TokenAuthenticationRule.Settings.TokenType.StaticToken
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenDef.AllowedPrefix
import tech.beshu.ror.accesscontrol.domain.{AuthorizationToken, AuthorizationTokenDef, Header, User}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.factory.RawRorSettingsBasedCoreFactory.CoreCreationError.RulesLevelCreationError
import tech.beshu.ror.accesscontrol.factory.decoders.common.*
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions
import tech.beshu.ror.accesscontrol.factory.decoders.rules.OptionalImpersonatorDefinitionOps
import tech.beshu.ror.accesscontrol.factory.decoders.rules.RuleBaseDecoder.RuleBaseDecoderWithoutAssociatedFields
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecoderOps
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecodingFailureUtils.decodingFailureFrom

final class TokenAuthenticationRuleDecoder(impersonatorsDef: Option[Definitions[ImpersonatorDef]],
                                           mocksProvider: MocksProvider,
                                           globalSettings: GlobalSettings)
  extends RuleBaseDecoderWithoutAssociatedFields[TokenAuthenticationRule] {

  override protected def decoder: Decoder[Block.RuleDefinition[TokenAuthenticationRule]] =
    TokenAuthenticationRuleDecoder
      .decoder
      .toSyncDecoder
      .map { settings =>
        RuleDefinition.create(new TokenAuthenticationRule(
          settings,
          globalSettings.userIdCaseSensitivity,
          impersonatorsDef.toImpersonation(mocksProvider)
        ))
      }
      .mapError(RulesLevelCreationError.apply)
      .decoder
}

private object TokenAuthenticationRuleDecoder {

  private val decoder: Decoder[TokenAuthenticationRule.Settings] =
    Decoder.instance { c =>
      for {
        username <- c.downField("username").as[User.Id]
        tokenValueStr <- c.downField("token").as[Option[NonEmptyString]]
        maybeCustomHeaderName <- c.downField("header").as[Option[Header.Name]]
        tokenType <- tokenTypeFrom(tokenValueStr, maybeCustomHeaderName)
      } yield TokenAuthenticationRule.Settings(
        user = username,
        tokenType = tokenType
      )
    }

  private def tokenTypeFrom(tokenValueStr: Option[NonEmptyString],
                            customHeaderName: Option[Header.Name]) = {
    val authTokenHeaderName = customHeaderName.getOrElse(Header.Name.authorization)
    tokenValueStr.flatMap(AuthorizationToken.from) match {
      case Some(authorizationToken) =>
        Right(StaticToken(
          AuthorizationTokenDef(authTokenHeaderName, AllowedPrefix.Any),
          authorizationToken
        ))
      case None =>
        errorFrom(s"Invalid token value: '${tokenValueStr.getOrElse("")}'")
    }
  }

  private def errorFrom(msg: String) = {
    Left(decodingFailureFrom(RulesLevelCreationError(Message(msg))))
  }
}

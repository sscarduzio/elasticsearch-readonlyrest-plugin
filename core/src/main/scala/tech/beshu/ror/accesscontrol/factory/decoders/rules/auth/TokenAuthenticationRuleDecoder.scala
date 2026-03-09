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

import cats.implicits.*
import eu.timepit.refined.types.string.NonEmptyString
import io.circe.Decoder
import tech.beshu.ror.accesscontrol.blocks.Block
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.definitions.ImpersonatorDef
import tech.beshu.ror.accesscontrol.blocks.mocks.MocksProvider
import tech.beshu.ror.accesscontrol.blocks.rules.auth.TokenAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.TokenAuthenticationRule.Settings.TokenType.{ApiKey, ServiceToken, StaticToken}
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenDef.AllowedPrefix.StrictlyDefined
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenDef.AllowedPrefix
import tech.beshu.ror.accesscontrol.domain.AuthorizationTokenPrefix.{api, bearer}
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
import tech.beshu.ror.constants.EsFeatureVersions
import tech.beshu.ror.es.{EsEnv, EsVersion}

final class TokenAuthenticationRuleDecoder(impersonatorsDef: Option[Definitions[ImpersonatorDef]],
                                           mocksProvider: MocksProvider,
                                           globalSettings: GlobalSettings,
                                           esEnv: EsEnv)
  extends RuleBaseDecoderWithoutAssociatedFields[TokenAuthenticationRule] {

  override protected def decoder: Decoder[Block.RuleDefinition[TokenAuthenticationRule]] = {
    implicit val esVersion: EsVersion = esEnv.esVersion
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
}

private object TokenAuthenticationRuleDecoder {

  private def decoder(implicit esVersion: EsVersion): Decoder[TokenAuthenticationRule.Settings] =
    Decoder.instance { c =>
      for {
        username <- c.downField("username").as[User.Id]
        tokenTypeStr <- c.downField("type").as[Option[NonEmptyString]]
        tokenValueStr <- c.downField("token").as[Option[NonEmptyString]]
        maybeCustomHeaderName <- c.downField("header").as[Option[Header.Name]]
        tokenType <- tokenTypeFrom(tokenTypeStr, tokenValueStr, maybeCustomHeaderName)
      } yield TokenAuthenticationRule.Settings(
        user = username,
        tokenType = tokenType
      )
    }

  private def tokenTypeFrom(tokenTypeStr: Option[NonEmptyString],
                            tokenValueStr: Option[NonEmptyString],
                            customHeaderName: Option[Header.Name])
                           (implicit esVersion: EsVersion) = {
    val authTokenHeaderName = customHeaderName.getOrElse(Header.Name.authorization)
    (tokenTypeStr.map(_.value), tokenValueStr, esVersion) match {
      case (None | Some("static"), Some(tokenValue), _) =>
        AuthorizationToken.from(tokenValue) match {
          case Some(authorizationToken) =>
            Right(StaticToken(
              AuthorizationTokenDef(authTokenHeaderName, AllowedPrefix.Any),
              authorizationToken
            ))
          case None =>
            errorFrom(s"Invalid token value: ${tokenValue.value.show}")
        }
      case (None | Some("static"), None, _) =>
        errorFrom(
          "Static token type requires the 'token' field. See: https://docs.readonlyrest.com/elasticsearch#token_authentication"
        )
      case (Some("service-token"), _, esVersion) if esVersion < EsFeatureVersions.serviceAccountTokenServiceSupport =>
        errorFrom(
          "Token type 'service-token' is supported by Elasticsearch version equal or greater than 7.14.0. See: https://docs.readonlyrest.com/elasticsearch#token_authentication"
        )
      case (Some("service-token"), Some(_), _) =>
        errorFrom(
          "You cannot define static 'token' value when token type is 'service-token'. See: https://docs.readonlyrest.com/elasticsearch#token_authentication"
        )
      case (Some("service-token"), None, _) =>
        Right(ServiceToken(
          AuthorizationTokenDef(headerName = authTokenHeaderName, allowedPrefix = StrictlyDefined(bearer))
        ))
      case (Some("api-key"), _, esVersion) if esVersion < EsFeatureVersions.apiKeyServiceSupport =>
        errorFrom(
          "Token type 'api-key' is supported by Elasticsearch version equal or greater than 7.14.0. See: https://docs.readonlyrest.com/elasticsearch#token_authentication"
        )
      case (Some("api-key"), Some(_), _) =>
        errorFrom(
          "You cannot define static 'token' value when token type is 'api-key'. See: https://docs.readonlyrest.com/elasticsearch#token_authentication"
        )
      case (Some("api-key"), None, _) =>
        Right(ApiKey(
          AuthorizationTokenDef(headerName = authTokenHeaderName, allowedPrefix = StrictlyDefined(api))
        ))
      case (Some(unknown), _, _) =>
        errorFrom(s"Unknown token type '$unknown'. See: https://docs.readonlyrest.com/elasticsearch#token_authentication")
    }
  }

  private def errorFrom(msg: String) = {
    Left(decodingFailureFrom(RulesLevelCreationError(Message(msg))))
  }
}

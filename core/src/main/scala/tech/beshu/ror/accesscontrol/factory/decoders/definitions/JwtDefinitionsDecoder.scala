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
package tech.beshu.ror.accesscontrol.factory.decoders.definitions

import io.circe.{Decoder, HCursor, Json}
import tech.beshu.ror.accesscontrol.domain.{AuthorizationTokenDef, Header, Jwt}
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef.{GroupsConfig, Name, SignatureCheckMethod}
import tech.beshu.ror.accesscontrol.blocks.definitions.{ExternalAuthenticationService, JwtDef}
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.accesscontrol.factory.RawRorConfigBasedCoreFactory.CoreCreationError.Reason.Message
import tech.beshu.ror.accesscontrol.utils.CirceOps.DecodingFailureOps.fromError
import tech.beshu.ror.accesscontrol.utils.CirceOps._
import tech.beshu.ror.accesscontrol.utils.CryptoOps.keyStringToPublicKey
import tech.beshu.ror.accesscontrol.utils.{ADecoder, SyncDecoder, SyncDecoderCreator}
import tech.beshu.ror.accesscontrol.factory.decoders.common._
import ExternalAuthenticationServicesDecoder.jwtExternalAuthenticationServiceDecoder
import cats.Id
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeResolvableVariableCreator

object JwtDefinitionsDecoder {

  def instance(httpClientFactory: HttpClientsFactory,
               variableCreator: RuntimeResolvableVariableCreator): ADecoder[Id, Definitions[JwtDef]] = {
    implicit val decoder: SyncDecoder[JwtDef] = SyncDecoderCreator.from(jwtDefDecoder(httpClientFactory, variableCreator))
    DefinitionsBaseDecoder.instance[Id, JwtDef]("jwt")
  }

  implicit val jwtDefNameDecoder: Decoder[Name] = DecoderHelpers.decodeStringLikeNonEmpty.map(Name.apply)

  private def jwtDefDecoder(implicit httpClientFactory: HttpClientsFactory,
                            variableCreator: RuntimeResolvableVariableCreator): Decoder[JwtDef] = {
    SyncDecoderCreator
      .instance { c =>
        for {
          name <- c.downField("name").as[Name]
          checkMethod <- signatureCheckMethod(c)
          headerName <- c.downField("header_name").as[Option[Header.Name]]
          authTokenPrefix <- c.downField("header_prefix").as[Option[String]]
          userClaim <- c.downField("user_claim").as[Option[Jwt.ClaimName]]
          groupsConfig <- c.as[Option[GroupsConfig]]
        } yield JwtDef(
          id = name,
          authorizationTokenDef = AuthorizationTokenDef(
            headerName.getOrElse(Header.Name.authorization),
            authTokenPrefix.getOrElse("Bearer ")
          ),
          checkMethod = checkMethod,
          userClaim = userClaim,
          groupsConfig = groupsConfig
        )
      }
      .mapError(DefinitionsLevelCreationError.apply)
      .decoder
  }

  /*
      JWT ALGO    FAMILY
      =======================
      NONE        None

      HS256       HMAC
      HS384       HMAC
      HS512       HMAC

      RS256       RSA
      RS384       RSA
      RS512       RSA
      PS256       RSA
      PS384       RSA
      PS512       RSA

      ES256       EC
      ES384       EC
      ES512       EC
    */
  private def signatureCheckMethod(c: HCursor)
                                  (implicit httpClientFactory: HttpClientsFactory,
                                   variableCreator: RuntimeResolvableVariableCreator): Decoder.Result[SignatureCheckMethod] = {
    def decodeSignatureKey =
      DecoderHelpers
        .decodeStringLikeWithSingleVarResolvedInPlace
        .tryDecode(c.downField("signature_key"))

    for {
      alg <- c.downField("signature_algo").as[Option[String]]
      checkMethod <- alg.map(_.toUpperCase) match {
        case Some("NONE") =>
          Decoder[ExternalAuthenticationService]
            .tryDecode(c
              .downField("external_validator")
              .withFocus(_.mapObject(_.add("name", Json.fromString("jwt"))))
            )
            .left.map(_.overrideDefaultErrorWith(DefinitionsLevelCreationError(Message("External validator has to be defined when signature algorithm is None"))))
            .map(SignatureCheckMethod.NoCheck.apply)
        case Some("HMAC") | None =>
          decodeSignatureKey
            .map(_.getBytes)
            .map(SignatureCheckMethod.Hmac.apply)
        case Some("RSA") =>
          decodeSignatureKey
            .flatMap { key =>
              keyStringToPublicKey("RSA", key).toEither
                .left.map(_ => fromError(CoreCreationError.DefinitionsLevelCreationError(Message(s"Key '$key' seems to be invalid"))))
            }
            .map(SignatureCheckMethod.Rsa.apply)
        case Some("EC") =>
          decodeSignatureKey
            .flatMap { key =>
              keyStringToPublicKey("EC", key).toEither
                .left.map(_ => fromError(CoreCreationError.DefinitionsLevelCreationError(Message(s"Key '$key' seems to be invalid"))))
            }
            .map(SignatureCheckMethod.Ec.apply)
        case Some(unknown) =>
          Left(fromError(
            DefinitionsLevelCreationError(Message(s"Unrecognised algorithm family '$unknown'. Should be either of: HMAC, EC, RSA, NONE"))
          ))
      }
    } yield checkMethod
  }

  private implicit val claimDecoder: Decoder[Jwt.ClaimName] = jsonPathDecoder.map(Jwt.ClaimName.apply)

  private implicit val groupsConfigDecoder: Decoder[GroupsConfig] = Decoder.instance { c =>
    for {
      groupIdsClaim
        <- c.downFields("roles_claim", "groups_claim", "group_ids_claim").as[Jwt.ClaimName]
      groupNamesClaim
        <- c.downFields("group_names_claim").as[Option[Jwt.ClaimName]]
    } yield GroupsConfig(groupIdsClaim, groupNamesClaim)
  }
}
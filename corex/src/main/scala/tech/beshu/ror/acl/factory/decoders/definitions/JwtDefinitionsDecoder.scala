package tech.beshu.ror.acl.factory.decoders.definitions

import io.circe.{Decoder, HCursor, Json}
import tech.beshu.ror.acl.aDomain.{ClaimName, Header}
import tech.beshu.ror.acl.blocks.definitions.JwtDef.{Name, SignatureCheckMethod}
import tech.beshu.ror.acl.blocks.definitions.{ExternalAuthenticationService, JwtDef}
import tech.beshu.ror.acl.factory.HttpClientsFactory
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.utils.CirceOps.DecodingFailureOps.fromError
import tech.beshu.ror.acl.utils.CirceOps._
import tech.beshu.ror.acl.utils.CryptoOps.keyStringToPublicKey
import tech.beshu.ror.acl.utils.StaticVariablesResolver

class JwtDefinitionsDecoder(httpClientFactory: HttpClientsFactory,
                            resolver: StaticVariablesResolver)
  extends DefinitionsBaseDecoder[JwtDef]("jwt")(
    JwtDefinitionsDecoder.jwtDefDecoder(httpClientFactory, resolver)
  )

object JwtDefinitionsDecoder {

  implicit val jwtDefNameDecoder: Decoder[Name] = DecoderHelpers.decodeStringLikeNonEmpty.map(Name.apply)

  private implicit val claimDecoder: Decoder[ClaimName] = DecoderHelpers.decodeStringLikeNonEmpty.map(ClaimName.apply)

  private def jwtDefDecoder(implicit httpClientFactory: HttpClientsFactory,
                            resolver: StaticVariablesResolver): Decoder[JwtDef] = {
    import tech.beshu.ror.acl.factory.decoders.common._
    Decoder
      .instance { c =>
        for {
          name <- c.downField("name").as[Name]
          checkMethod <- signatureCheckMethod(c)
          headerName <- c.downField("header_name").as[Option[Header.Name]]
          userClaim <- c.downField("user_claim").as[Option[ClaimName]]
          groupsClaim <- c.downField("roles_claim").as[Option[ClaimName]]
        } yield JwtDef(name, headerName.getOrElse(Header.Name.authorization), checkMethod, userClaim, groupsClaim)
      }
      .mapError(DefinitionsLevelCreationError.apply)
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
                                   resolver: StaticVariablesResolver): Decoder.Result[SignatureCheckMethod] = {
    def decodeSignatureKey =
      DecoderHelpers
        .decodeStringLikeWithVarResolvedInPlace
        .tryDecode(c.downField("signature_key"))

    import ExternalAuthenticationServicesDecoder.jwtExternalAuthenticationServiceDecoder
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
                .left.map(_ => fromError(AclCreationError.DefinitionsLevelCreationError(Message(s"Key '$key' seems to be invalid"))))
            }
            .map(SignatureCheckMethod.Rsa.apply)
        case Some("EC") =>
          decodeSignatureKey
            .flatMap { key =>
              keyStringToPublicKey("EC", key).toEither
                .left.map(_ => fromError(AclCreationError.DefinitionsLevelCreationError(Message(s"Key '$key' seems to be invalid"))))
            }
            .map(SignatureCheckMethod.Ec.apply)
        case Some(unknown) =>
          Left(fromError(
            DefinitionsLevelCreationError(Message(s"Unrecognised algorithm family '$unknown'. Should be either of: HMAC, EC, RSA, NONE"))
          ))
      }
    } yield checkMethod
  }
}
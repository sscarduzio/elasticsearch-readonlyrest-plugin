package tech.beshu.ror.acl.factory.decoders.definitions

import io.circe.{Decoder, HCursor}
import tech.beshu.ror.acl.aDomain.Header
import tech.beshu.ror.acl.blocks.definitions.JwtDef.{Claim, Name, SignatureCheckMethod}
import tech.beshu.ror.acl.blocks.definitions.{ExternalAuthenticationService, JwtDef}
import tech.beshu.ror.acl.factory.HttpClientsFactory
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.acl.factory.RorAclFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.utils.CirceOps.DecodingFailureOps.fromError
import tech.beshu.ror.acl.utils.CirceOps._
import tech.beshu.ror.acl.utils.CryptoOps.keyStringToPublicKey

class JwtDefinitionsDecoder(httpClientFactory: HttpClientsFactory)
  extends DefinitionsBaseDecoder[JwtDef]("jwt")(
    JwtDefinitionsDecoder.jwtDefDecoder(httpClientFactory)
  )

object JwtDefinitionsDecoder {

  implicit val jwtDefNameDecoder: Decoder[Name] = Decoder.decodeString.map(Name.apply)

  private implicit val claimDecoder: Decoder[Claim] = Decoder.decodeString.map(Claim.apply)

  private def jwtDefDecoder(implicit httpClientFactory: HttpClientsFactory): Decoder[JwtDef] = {
    import tech.beshu.ror.acl.factory.decoders.common._
    Decoder
      .instance { c =>
        for {
          name <- c.downField("name").as[Name]
          headerName <- c.downField("header_name").as[Header.Name]
          userClaim <- c.downField("user_claim").as[Option[Claim]]
          groupsClaim <- c.downField("roles_claim").as[Option[Claim]]
          checkMethod <- signatureCheckMethod(c)
        } yield JwtDef(name, headerName, checkMethod, userClaim, groupsClaim)
      }
      .mapError(DefinitionsLevelCreationError.apply)
  }

  private def signatureCheckMethod(c: HCursor)
                                  (implicit httpClientFactory: HttpClientsFactory): Decoder.Result[SignatureCheckMethod] = {
    def decodeSignatureKey =
      DecoderHelpers
        .decodeStringLikeWithVarResolvedInPlace
        .tryDecode(c.downField("signature_key"))

    import ExternalAuthenticationServicesDecoder.jwtExternalAuthenticationServiceDecoder
    for {
      alg <- c.downField("signature_algo").as[Option[String]]
      checkMethod <- alg.map(_.toUpperCase) match {
        case Some("NONE") | None =>
          Decoder[ExternalAuthenticationService]
            .tryDecode(c.downField("external_validator"))
            .left.map(_.overrideDefaultErrorWith(DefinitionsLevelCreationError(Message("External validator has to be defined when signature algorithm is None"))))
            .map(SignatureCheckMethod.NoCheck.apply)
        case Some("HMAC") =>
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
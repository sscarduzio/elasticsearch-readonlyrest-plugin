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
package tech.beshu.ror.acl.factory.decoders.definitions

import cats.Id
import io.circe.{Decoder, HCursor}
import tech.beshu.ror.acl.blocks.definitions.RorKbnDef
import tech.beshu.ror.acl.blocks.definitions.RorKbnDef.{Name, SignatureCheckMethod}
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.DefinitionsLevelCreationError
import tech.beshu.ror.acl.factory.RawRorConfigBasedCoreFactory.AclCreationError.Reason.Message
import tech.beshu.ror.acl.utils.CirceOps.DecoderHelpers
import tech.beshu.ror.acl.utils.CirceOps.DecodingFailureOps.fromError
import tech.beshu.ror.acl.utils.CryptoOps.keyStringToPublicKey
import tech.beshu.ror.acl.utils.{ADecoder, SyncDecoder, SyncDecoderCreator}
import tech.beshu.ror.providers.EnvVarsProvider

object RorKbnDefinitionsDecoder {

  def instance(): ADecoder[Id, Definitions[RorKbnDef]] = {
    implicit val decoder: SyncDecoder[RorKbnDef] = SyncDecoderCreator.from(RorKbnDefinitionsDecoder.rorKbnDefDecoder)
    DefinitionsBaseDecoder.instance[Id, RorKbnDef]("ror_kbn")
  }

  implicit val rorKbnDefNameDecoder: Decoder[RorKbnDef.Name] = DecoderHelpers.decodeStringLikeNonEmpty.map(Name.apply)

  private val rorKbnDefDecoder: Decoder[RorKbnDef] =  {
    SyncDecoderCreator
      .instance { c =>
        for {
          name <- c.downField("name").as[Name]
          checkMethod <- signatureCheckMethod(c)
        } yield RorKbnDef(name, checkMethod)
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
  private def signatureCheckMethod(c: HCursor): Decoder.Result[SignatureCheckMethod] = {
    def decodeSignatureKey =
      DecoderHelpers
        .decodeStringLikeWithSingleVarResolvedInPlace
        .tryDecode(c.downField("signature_key"))
    for {
      alg <- c.downField("signature_algo").as[Option[String]]
      checkMethod <- alg.map(_.toUpperCase) match {
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

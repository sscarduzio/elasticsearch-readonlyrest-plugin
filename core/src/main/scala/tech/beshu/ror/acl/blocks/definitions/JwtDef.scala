package tech.beshu.ror.acl.blocks.definitions

import java.security.PublicKey

import cats.{Eq, Show}
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.acl.aDomain.Header
import tech.beshu.ror.acl.blocks.definitions.JwtDef.{Claim, Name, SignatureCheckMethod}
import tech.beshu.ror.acl.factory.decoders.definitions.Definitions.Item

final case class JwtDef(id: Name,
                        headerName: Header.Name,
                        checkMethod: SignatureCheckMethod,
                        userClaim: Option[Claim],
                        groupsClaim: Option[Claim])
  extends Item {

  override type Id = Name
  override implicit val show: Show[Name] = JwtDef.nameShow
}
object JwtDef {
  final case class Name(value: NonEmptyString)

  sealed trait SignatureCheckMethod
  object SignatureCheckMethod {
    final case class NoCheck(service: ExternalAuthenticationService) extends SignatureCheckMethod
    final case class Hmac(key: Array[Byte]) extends SignatureCheckMethod
    final case class Rsa(pubKey: PublicKey) extends SignatureCheckMethod
    final case class Ec(pubKey: PublicKey) extends SignatureCheckMethod
  }

  final case class Claim(value: NonEmptyString)

  implicit val nameEq: Eq[Name] = Eq.fromUniversalEquals
  implicit val nameShow: Show[Name] = Show.show(_.value.value)
}
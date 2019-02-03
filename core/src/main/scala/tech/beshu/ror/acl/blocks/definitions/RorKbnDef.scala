package tech.beshu.ror.acl.blocks.definitions

import java.security.PublicKey

import cats.{Eq, Show}
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.acl.blocks.definitions.RorKbnDef.{Name, SignatureCheckMethod}
import tech.beshu.ror.acl.factory.decoders.definitions.Definitions.Item

final case class RorKbnDef(id: Name,
                           checkMethod: SignatureCheckMethod)
  extends Item {

  override type Id = Name
  override implicit val show: Show[Name] = RorKbnDef.nameShow
}
object RorKbnDef {
  final case class Name(value: NonEmptyString)

  sealed trait SignatureCheckMethod
  object SignatureCheckMethod {
    final case class Hmac(key: Array[Byte]) extends SignatureCheckMethod
    final case class Rsa(pubKey: PublicKey) extends SignatureCheckMethod
    final case class Ec(pubKey: PublicKey) extends SignatureCheckMethod
  }

  implicit val nameEq: Eq[Name] = Eq.fromUniversalEquals
  implicit val nameShow: Show[Name] = Show.show(_.value.value)
}

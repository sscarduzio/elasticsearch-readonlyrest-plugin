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
package tech.beshu.ror.accesscontrol.blocks.definitions

import java.security.PublicKey
import cats.{Eq, Show}
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.blocks.definitions.JwtDef.{Name, SignatureCheckMethod}
import tech.beshu.ror.accesscontrol.domain.{AuthorizationTokenDef, Jwt}
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions.Item

final case class JwtDef(id: Name,
                        authorizationTokenDef: AuthorizationTokenDef,
                        checkMethod: SignatureCheckMethod,
                        userClaim: Option[Jwt.ClaimName],
                        groupsClaim: Option[Jwt.ClaimName])
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

  implicit val nameEq: Eq[Name] = Eq.fromUniversalEquals
  implicit val nameShow: Show[Name] = Show.show(_.value.value)
}
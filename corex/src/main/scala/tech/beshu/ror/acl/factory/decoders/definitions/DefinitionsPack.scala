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

import cats.Show
import tech.beshu.ror.acl.blocks.definitions._

import scala.language.higherKinds

final case class DefinitionsPack(proxies: Definitions[ProxyAuth],
                                 users: Definitions[UserDef],
                                 authenticationServices: Definitions[ExternalAuthenticationService],
                                 authorizationServices: Definitions[ExternalAuthorizationService],
                                 jwts: Definitions[JwtDef],
                                 rorKbns: Definitions[RorKbnDef],
                                 ldaps: Definitions[LdapService])

final case class Definitions[Item](items: Set[Item]) extends AnyVal
object Definitions {
  trait Item {
    type Id
    def id: Id
    implicit def show: Show[Id]
  }

}
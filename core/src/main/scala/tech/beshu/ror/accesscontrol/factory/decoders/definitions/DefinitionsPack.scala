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

import cats.Show
import tech.beshu.ror.accesscontrol.blocks.definitions.*
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.factory.decoders.definitions.Definitions.Item

final case class DefinitionsPack(proxies: Definitions[ProxyAuth],
                                 users: Definitions[UserDef],
                                 authenticationServices: Definitions[ExternalAuthenticationService],
                                 authorizationServices: Definitions[ExternalAuthorizationService],
                                 jwts: Definitions[JwtDef],
                                 rorKbns: Definitions[RorKbnDef],
                                 ldaps: Definitions[LdapService],
                                 impersonators: Definitions[ImpersonatorDef],
                                 variableTransformationAliases: Definitions[VariableTransformationAliasDef])

final case class Definitions[ITEM <: Item](items: List[ITEM]) extends AnyVal
object Definitions {
  trait Item {
    type Id
    def id: Id
    def idShow: Show[Id]
  }
}
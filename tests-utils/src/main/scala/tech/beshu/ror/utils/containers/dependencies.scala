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
package tech.beshu.ror.utils.containers

import monix.eval.Coeval

object dependencies {

  def ldap(name: String, ldapInitScript: String): DependencyDef = {
    DependencyDef(
      name = name,
      Coeval(LdapContainer.create(name, ldapInitScript)),
      originalPort = LdapContainer.defaults.ldap.port)
  }

  def wiremock(name: String, mappings: String*): DependencyDef = {
    DependencyDef(name,
      containerCreator = Coeval(new WireMockScalaAdapter(WireMockContainer.create(mappings: _*))),
      originalPort = WireMockContainer.WIRE_MOCK_PORT)
  }
}

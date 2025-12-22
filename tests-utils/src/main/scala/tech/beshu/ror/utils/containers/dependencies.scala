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

import tech.beshu.ror.utils.misc.OsUtils
import tech.beshu.ror.utils.misc.OsUtils.CurrentOs

object dependencies {

  def ldap(name: String, ldapInitScript: String): DependencyDef = {
    val ldap = LdapContainer.create(name, ldapInitScript)
    DependencyDef(
      name = name,
      container = ldap,
      originalPort = ldap.originalPort
    )
  }

  def ldap(name: String, ldap: LdapContainer): DependencyDef = DependencyDef(
    name = name,
    container = ldap,
    originalPort = ldap.originalPort
  )

  def wiremock(name: String, portWhenRunningOnWindows: Int, mappings: String*): DependencyDef = {
    val wiremock = Wiremock.create(mappings.toList, portWhenRunningOnWindows)
    DependencyDef(
      name = name,
      container = wiremock.container,
      originalPort = wiremock.originalPort,
    )
  }

  def es(name: String, container: EsContainer): DependencyDef = {
    OsUtils.currentOs match {
      case CurrentOs.Windows =>
        DependencyDef(
          name = name,
          container = container,
          originalPort = container.port
        )
      case CurrentOs.OtherThanWindows =>
        DependencyDef(
          name = name,
          container = container,
          originalPort = 9200
        )
    }
  }

  def toxiproxy(name: String, container: ToxiproxyContainer[_]): DependencyDef = {
    DependencyDef(
      name = name,
      container = container,
      originalPort = ToxiproxyContainer.proxiedPort
    )
  }
}

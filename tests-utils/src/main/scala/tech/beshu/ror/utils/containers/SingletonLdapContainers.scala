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

import tech.beshu.ror.utils.containers.LdapContainer.InitScriptSource

object SingletonLdapContainers {

  val ldap1: NonStoppableLdapContainer = {
    val ldap = new NonStoppableLdapContainer("LDAP1", "test_example.ldif")
    ldap.start()
    ldap
  }

  val ldap1Backup: NonStoppableLdapContainer = {
    val ldap = new NonStoppableLdapContainer("LDAP1_BACKUP", "test_example.ldif")
    ldap.start()
    ldap
  }

  val ldap2: NonStoppableLdapContainer = {
    val ldap = new NonStoppableLdapContainer("LDAP2", "test_example2.ldif")
    ldap.start()
    ldap
  }

  class NonStoppableLdapContainer(name: String, ldapInitScript: InitScriptSource)
    extends LdapContainer(name, ldapInitScript) {

    override def stop(): Unit = ()
  }
}

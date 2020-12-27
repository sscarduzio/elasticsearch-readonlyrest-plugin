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

import com.dimafeng.testcontainers.Container
import tech.beshu.ror.utils.containers.LdapContainer.InitScriptSource

class LdapWithDnsContainer(name: String, ldapInitScript: InitScriptSource)
  extends Container {

  private val ldapContainer = new LdapContainer(name, ldapInitScript)

  private var dnsContainer: Option[DnsServerContainer] = None

  def dnsPort: Int = dnsContainer.getOrElse(throw new Exception("DNS container hasn't been started yet")).dnsPort

  override def start(): Unit = {
    ldapContainer.start()
    dnsContainer = Option(new DnsServerContainer(ldapContainer.ldapPort))
    dnsContainer.foreach(_.start())
  }

  override def stop(): Unit = {
    ldapContainer.stop()
    dnsContainer.foreach(_.stop())
  }

}

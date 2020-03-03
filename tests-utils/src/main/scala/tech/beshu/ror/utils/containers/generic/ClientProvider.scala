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
package tech.beshu.ror.utils.containers.generic

import java.util.Optional

import tech.beshu.ror.utils.containers.generic.ClientProvider.adminCredentials
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.Tuple

trait ClientProvider {
  def client(user: String, pass: String): RestClient

  def adminClient: RestClient = client(adminCredentials._1, adminCredentials._2)
}

object ClientProvider {
  val adminCredentials: (String, String) = ("admin", "container")
}

trait TargetEsContainer {
  def targetEsContainer: EsContainer
}

trait CallingEsDirectly extends ClientProvider {
  this: TargetEsContainer =>

  override def client(user: String, pass: String): RestClient = targetEsContainer.client(user, pass)
}

trait CallingProxy extends ClientProvider {
  def proxyPort: Int

  override def client(user: String, pass: String): RestClient = new RestClient(false, "localhost", proxyPort, Optional.of(Tuple.from(user, pass)))
}


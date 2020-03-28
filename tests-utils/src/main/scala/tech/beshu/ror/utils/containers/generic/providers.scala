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

import cats.data.NonEmptyList
import tech.beshu.ror.utils.containers.generic.providers.ClientProvider.adminCredentials
import tech.beshu.ror.utils.httpclient.RestClient
import tech.beshu.ror.utils.misc.Tuple

object providers {

  trait ClientProvider {
    def client(user: String, pass: String): RestClient

    def adminClient: RestClient = client(adminCredentials._1, adminCredentials._2)
  }

  object ClientProvider {
    val adminCredentials: (String, String) = ("admin", "container")
  }

  trait RorConfigFileNameProvider {
    def rorConfigFileName: String
  }

  trait MultipleClients {
    def clients: NonEmptyList[ClientProvider]
  }

  trait MultipleEsTargets {
    def esTargets: NonEmptyList[EsContainer]
  }

  trait SingleEsTarget extends MultipleEsTargets {
    def targetEs: EsContainer

    override def esTargets: NonEmptyList[EsContainer] = NonEmptyList.one(targetEs)
  }

  trait SingleClient extends ClientProvider with MultipleClients {
    override def client(user: String, pass: String): RestClient = clients.head.client(user, pass)
  }

  trait CallingEsDirectly extends MultipleClients {
    this: MultipleEsTargets =>

    override def clients: NonEmptyList[ClientProvider] = {
      esTargets.map { target =>
        (user: String, pass: String) => target.client(user, pass)
      }
    }
  }

  trait CallingProxy extends MultipleClients {
    def proxyPorts: NonEmptyList[Int]

    override def clients: NonEmptyList[ClientProvider] = {
      proxyPorts.map { port =>
        (user: String, pass: String) => new RestClient(false, "localhost", port, Optional.of(Tuple.from(user, pass)))
      }
    }
  }

}
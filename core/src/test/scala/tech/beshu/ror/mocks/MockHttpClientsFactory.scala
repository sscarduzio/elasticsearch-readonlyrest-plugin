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
package tech.beshu.ror.mocks

import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.accesscontrol.factory.HttpClientsFactory.HttpClient
import tech.beshu.ror.accesscontrol.factory.{HttpClientsFactory, SimpleHttpClient}

object MockHttpClientsFactory extends HttpClientsFactory {
  override def create(config: HttpClientsFactory.Config): HttpClient = new SimpleHttpClient[Task] {
    override def send(request: HttpClient.Request)
                     (implicit requestId: RequestId): Task[HttpClient.Response] =
      throw new IllegalStateException("Cannot use it. It's just a mock")

    override def close(): Task[Unit] = Task.unit
  }
  override def shutdown(): Unit = {}
}

class MockHttpClientsFactoryWithFixedHttpClient(httpClient: HttpClient) extends HttpClientsFactory {
  override def create(config: HttpClientsFactory.Config): HttpClient = httpClient
  override def shutdown(): Unit = {}
}
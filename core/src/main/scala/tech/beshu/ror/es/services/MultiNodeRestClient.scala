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
package tech.beshu.ror.es.services

import monix.eval.Task
import tech.beshu.ror.accesscontrol.domain.RequestId
import tech.beshu.ror.es.services.MultiNodeRestClient.*

trait MultiNodeRestClient[Req, Resp] {

  def perform(request: Req)(
      using RequestId
  ): Task[Resp]

  def close(): Unit
}

object MultiNodeRestClient {

  trait RequestExecutor[Req, Resp] {
    def execute(request: Req): Task[Resp]

    def close(): Unit
  }

}

final class RoundRobinClient[Req, Resp](executor: RequestExecutor[Req, Resp]) extends MultiNodeRestClient[Req, Resp] {

  override def perform(request: Req)(
      using RequestId
  ): Task[Resp] = executor.execute(request)

  override def close(): Unit = executor.close()
}

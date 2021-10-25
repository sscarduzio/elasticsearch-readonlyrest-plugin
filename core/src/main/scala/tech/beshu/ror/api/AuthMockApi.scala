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
package tech.beshu.ror.api

import monix.eval.Task
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.mocks.MutableMocksProviderWithCachePerRequest
import tech.beshu.ror.boot.RorSchedulers

class AuthMockApi(mockProvider: MutableMocksProviderWithCachePerRequest) {

  import AuthMockApi._

  def call(request: AuthMockRequest)
          (implicit requestId: RequestId): Task[AuthMockResponse] = {
    val apiCallResult = request.aType match {
      case AuthMockRequest.Type.UpdateAuthMock => updateAuthMock(request.body)
      case AuthMockRequest.Type.InvalidateAuthMock => invalidateAuthMock()
    }
    apiCallResult
      .executeOn(RorSchedulers.restApiScheduler)
  }

  private def updateAuthMock(body: String)
                            (implicit requestId: RequestId): Task[AuthMockResponse] = ???

  private def invalidateAuthMock()
                                (implicit requestId: RequestId): Task[AuthMockResponse] = {
    Task
      .delay(mockProvider.invalidate())
      .map(_ => AuthMockApi.Success("Auth mock settings invalidated"))
  }
}
object AuthMockApi {

  final case class AuthMockRequest(aType: AuthMockRequest.Type,
                                   body: String)
  object AuthMockRequest {
    sealed trait Type
    object Type {
      case object UpdateAuthMock extends Type
      case object InvalidateAuthMock extends Type
    }
  }

  sealed trait AuthMockResponse
  object AuthMockResponse {
    def internalError: AuthMockResponse = Failure("Internal error")
    def notAvailable: AuthMockResponse = Failure("Service not available")
  }
  final case class Success(message: String) extends AuthMockResponse
  final case class Failure(message: String) extends AuthMockResponse
}
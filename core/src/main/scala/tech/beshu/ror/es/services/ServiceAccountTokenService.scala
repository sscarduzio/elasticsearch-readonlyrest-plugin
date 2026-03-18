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
import tech.beshu.ror.accesscontrol.domain.{AuthorizationToken, RequestId}
import tech.beshu.ror.accesscontrol.utils.AsyncCacheableAction

trait ServiceAccountTokenService {

  def validateToken(token: AuthorizationToken)
                   (implicit requestId: RequestId): Task[Boolean]
}

class CacheableServiceAccountTokenServiceDecorator(underlying: ServiceAccountTokenService)
  extends ServiceAccountTokenService {

  private lazy val cacheableValidateToken = new AsyncCacheableAction[AuthorizationToken, Boolean](
    action = (token, requestId) => underlying.validateToken(token)(requestId)
  )

  override def validateToken(token: AuthorizationToken)
                            (implicit requestId: RequestId): Task[Boolean] =
    cacheableValidateToken.call(token)
}

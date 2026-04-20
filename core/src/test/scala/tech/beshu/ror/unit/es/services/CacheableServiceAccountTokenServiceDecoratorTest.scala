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
package tech.beshu.ror.unit.es.services

import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.{AuthorizationToken, AuthorizationTokenPrefix, RequestId}
import tech.beshu.ror.es.services.{CacheableServiceAccountTokenServiceDecorator, ServiceAccountTokenService}
import tech.beshu.ror.utils.RefinedUtils.nes
import tech.beshu.ror.utils.WithDummyRequestIdSupport

class CacheableServiceAccountTokenServiceDecoratorTest
  extends AnyWordSpec
    with MockFactory
    with WithDummyRequestIdSupport {

  "CacheableServiceAccountTokenServiceDecorator" when {
    "validateToken is called" should {
      "delegate to the underlying service and return its result" in {
        val decorator = new CacheableServiceAccountTokenServiceDecorator(mockFor(tokenA -> true))

        val result = decorator.validateToken(tokenA).runSyncUnsafe()

        result should be(true)
      }
      "call the underlying service only once for the same token" in {
        val decorator = new CacheableServiceAccountTokenServiceDecorator(mockFor(tokenA -> true))

        val result = for {
          r1 <- decorator.validateToken(tokenA)
          r2 <- decorator.validateToken(tokenA)
          r3 <- decorator.validateToken(tokenA)
        } yield {
          r1 should be(true)
          r2 should be(true)
          r3 should be(true)
        }
        result.runSyncUnsafe()
      }
      "call the underlying service separately for each distinct token" in {
        val decorator = new CacheableServiceAccountTokenServiceDecorator(mockFor(tokenA -> true, tokenB -> false))

        val result = for {
          r1 <- decorator.validateToken(tokenA)
          r2 <- decorator.validateToken(tokenB)
          r3 <- decorator.validateToken(tokenA)
          r4 <- decorator.validateToken(tokenB)
        } yield {
          r1 should be(true)
          r2 should be(false)
          r3 should be(true)
          r4 should be(false)
        }
        result.runSyncUnsafe()
      }
    }
  }

  private def mockFor(expectations: (AuthorizationToken, Boolean)*): ServiceAccountTokenService = {
    val service = mock[ServiceAccountTokenService]
    expectations.foreach { case (token, result) =>
      (service.validateToken(_: AuthorizationToken)(_: RequestId))
        .expects(token, *)
        .returning(Task.now(result))
        .once()
    }
    service
  }

  private lazy val tokenA = AuthorizationToken(AuthorizationTokenPrefix.NoPrefix, nes("sa-token-a"))
  private lazy val tokenB = AuthorizationToken(AuthorizationTokenPrefix.NoPrefix, nes("sa-token-b"))
}

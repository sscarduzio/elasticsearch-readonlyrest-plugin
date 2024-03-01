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
package tech.beshu.ror.unit.acl.blocks.definitions.ldap

import com.unboundid.ldap.sdk.{LDAPSearchException, ResultCode}
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.exceptions.ExecutionRejectedException
import org.scalamock.scalatest.MockFactory
import org.scalatest.Assertion
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.definitions.CircuitBreakerConfig
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService.Name
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap._
import tech.beshu.ror.accesscontrol.domain.{PlainTextSecret, User}

import scala.concurrent.duration._
import scala.language.postfixOps
import scala.reflect.ClassTag

class CircuitBreakerLdapAuthenticationServiceDecoratorTests
  extends AnyWordSpec
    with MockFactory {

  "An CircuitBreaker decorated LdapAuthenticationService" should {
    "close circuit breaker after 2 failed attempts" in {
      val authenticationService = createCircuitBreakerDecoratedSimpleAuthenticationService {
        val service = mock[LdapAuthenticationService]
        (() => service.id).expects().returning(Name("ldap-mock")).anyNumberOfTimes()
        (service.authenticate _).expects(*, *).returning(authenticated).once()
        (service.authenticate _).expects(*, *).returning(timeoutLDAPException).twice()
        service
      }

      authenticationService.assertSuccessfulAuthentication
      authenticationService.assertFailedAuthentication[LDAPSearchException]
      authenticationService.assertFailedAuthentication[LDAPSearchException]
      authenticationService.assertFailedAuthentication[ExecutionRejectedException]
      authenticationService.assertFailedAuthentication[ExecutionRejectedException]
    }
    "close circuit breaker after 2 failed attempts, but open it later" in {
      val authenticationService = createCircuitBreakerDecoratedSimpleAuthenticationService {
        val service = mock[LdapAuthenticationService]
        (() => service.id).expects().returning(LdapService.Name("ldap-mock")).anyNumberOfTimes()
        (service.authenticate _).expects(*, *).returning(authenticated).once()
        (service.authenticate _).expects(*, *).returning(timeoutLDAPException).twice()
        (service.authenticate _).expects(*, *).returning(authenticated).twice()
        service
      }

      authenticationService.assertSuccessfulAuthentication
      authenticationService.assertFailedAuthentication[LDAPSearchException]
      authenticationService.assertFailedAuthentication[LDAPSearchException]
      authenticationService.assertFailedAuthentication[ExecutionRejectedException]
      Thread.sleep(1250)
      authenticationService.assertSuccessfulAuthentication
      authenticationService.assertSuccessfulAuthentication
    }
    "close circuit breaker after 2 failed attempts and keep it closed because of network issues" in {
      val authenticationService = createCircuitBreakerDecoratedSimpleAuthenticationService {
        val service = mock[LdapAuthenticationService]
        (() => service.id).expects().returning(LdapService.Name("ldap-mock")).anyNumberOfTimes()
        (service.authenticate _).expects(*, *).returning(authenticated).once()
        (service.authenticate _).expects(*, *).returning(timeoutLDAPException).repeat(3)
        service
      }

      authenticationService.assertSuccessfulAuthentication
      authenticationService.assertFailedAuthentication[LDAPSearchException]
      authenticationService.assertFailedAuthentication[LDAPSearchException]
      authenticationService.assertFailedAuthentication[ExecutionRejectedException]
      Thread.sleep(1250)
      authenticationService.assertFailedAuthentication[LDAPSearchException]
      authenticationService.assertFailedAuthentication[ExecutionRejectedException]
    }
  }

  private def createCircuitBreakerDecoratedSimpleAuthenticationService(authenticationService: LdapAuthenticationService) = {
    new CircuitBreakerLdapAuthenticationServiceDecorator(
      authenticationService,
      CircuitBreakerConfig(
        maxFailures = 2,
        resetDuration = Refined.unsafeApply(1 second))
    )
  }

  private lazy val authenticated = Task.now(true)
  private lazy val timeoutLDAPException = Task.raiseError(new LDAPSearchException(ResultCode.TIMEOUT, "timeout"))

  private implicit class LdapAuthenticationServiceOps(authenticationService: LdapAuthenticationService) {
    def assertSuccessfulAuthentication: Assertion = {
      authenticationService
        .authenticate(User.Id("morgan"), PlainTextSecret("user1"))
        .runSyncUnsafe() should be(true)
    }

    def assertFailedAuthentication[T: ClassTag]: Assertion = {
      an[T] should be thrownBy authenticationService
        .authenticate(User.Id("morgan"), PlainTextSecret("user1"))
        .runSyncUnsafe()
    }
  }
}

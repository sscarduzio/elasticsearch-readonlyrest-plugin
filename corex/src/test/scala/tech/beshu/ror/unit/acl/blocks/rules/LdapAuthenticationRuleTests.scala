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
package tech.beshu.ror.unit.acl.blocks.rules

import monix.eval.Task
import org.scalamock.scalatest.MockFactory
import org.scalatest.WordSpec
import org.scalatest.Matchers._
import monix.execution.Scheduler.Implicits.global
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.definitions.ldap.LdapAuthenticationService
import tech.beshu.ror.acl.blocks.rules.LdapAuthenticationRule
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult
import tech.beshu.ror.acl.domain.{LoggedUser, Secret, User}
import tech.beshu.ror.acl.domain.User.Id
import tech.beshu.ror.mocks.MockRequestContext
import tech.beshu.ror.utils.TestsUtils.basicAuthHeader

class LdapAuthenticationRuleTests extends WordSpec with MockFactory {

  "An LdapAuthenticationRule" should {
    "match" when {
      "LDAP service authenticates user" in {
        val requestContext = MockRequestContext.default.copy(headers = Set(basicAuthHeader("admin:pass")))
        val blockContext = mock[BlockContext]
        val modifiedBlockContext = mock[BlockContext]
        (blockContext.withLoggedUser _).expects(LoggedUser(Id("admin"))).returning(modifiedBlockContext)

        val service = mock[LdapAuthenticationService]
        (service.authenticate _).expects(User.Id("admin"), Secret("pass")).returning(Task.now(true))

        val rule = new LdapAuthenticationRule(LdapAuthenticationRule.Settings(service))
        rule.check(requestContext, blockContext).runSyncStep shouldBe  Right(RuleResult.Fulfilled(modifiedBlockContext))
      }
    }
    "not match" when {
      "LDAP service doesn't authenticate user" in {
        val requestContext = MockRequestContext.default.copy(headers = Set(basicAuthHeader("admin:pass")))
        val blockContext = mock[BlockContext]

        val service = mock[LdapAuthenticationService]
        (service.authenticate _).expects(User.Id("admin"), Secret("pass")).returning(Task.now(false))

        val rule = new LdapAuthenticationRule(LdapAuthenticationRule.Settings(service))
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(RuleResult.Rejected)
      }
      "there is no basic auth header" in {
        val requestContext = MockRequestContext.default
        val blockContext = mock[BlockContext]
        val service = mock[LdapAuthenticationService]

        val rule = new LdapAuthenticationRule(LdapAuthenticationRule.Settings(service))
        rule.check(requestContext, blockContext).runSyncStep shouldBe Right(RuleResult.Rejected)
      }
      "LDAP service fails" in {
        val requestContext = MockRequestContext.default.copy(headers = Set(basicAuthHeader("admin:pass")))
        val blockContext = mock[BlockContext]

        val service = mock[LdapAuthenticationService]
        (service.authenticate _).expects(User.Id("admin"), Secret("pass")).returning(Task.raiseError(TestException("Cannot reach LDAP")))

        val rule = new LdapAuthenticationRule(LdapAuthenticationRule.Settings(service))
        val thrown = the [TestException] thrownBy rule.check(requestContext, blockContext).runSyncUnsafe()
        thrown.getMessage should equal ("Cannot reach LDAP")
      }
    }
  }

  private sealed case class TestException(message: String) extends Exception(message)
}

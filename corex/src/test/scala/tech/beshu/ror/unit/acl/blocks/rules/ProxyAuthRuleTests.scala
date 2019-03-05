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

import cats.data.NonEmptySet
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.acl.domain.User.Id
import tech.beshu.ror.acl.domain.{Header, LoggedUser, User}
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.ProxyAuthRule
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.utils.TestsUtils._

class ProxyAuthRuleTests extends WordSpec with MockFactory {

  "A ProxyAuthRule" should {
    "match" when {
      "one user id is configured and the same id can be find in auth header" in {
        assertMatchRule(
          settings = ProxyAuthRule.Settings(NonEmptySet.of(User.Id("userA")), headerNameFrom("custom-user-auth-header")),
          header = headerFrom("custom-user-auth-header" -> "userA")
        )
      }
      "several user ids are configured and one of them can be find in auth header" in {
        assertMatchRule(
          settings = ProxyAuthRule.Settings(
            NonEmptySet.of(User.Id("userA"), User.Id("userB"), User.Id("userC")),
            headerNameFrom("custom-user-auth-header")
          ),
          header = headerFrom("custom-user-auth-header" -> "userB")
        )
      }
    }
    "not match" when {
      "none of configured user ids corresponds to the auth header one" in {
        assertNotMatchRule(
          settings = ProxyAuthRule.Settings(
            NonEmptySet.of(User.Id("userA"), User.Id("userB"), User.Id("userC")),
            headerNameFrom("custom-user-auth-header")
          ),
          header = headerFrom("custom-user-auth-header" -> "userD")
        )
      }
      "user id is passed in different header than the configured one" in {
        assertNotMatchRule(
          settings = ProxyAuthRule.Settings(
            NonEmptySet.of(User.Id("userA")),
            headerNameFrom("custom-user-auth-header")
          ),
          header = headerFrom("X-Forwarded-User" -> "userD")
        )
      }
    }
  }

  private def assertMatchRule(settings: ProxyAuthRule.Settings, header: Header) =
    assertRule(settings, header, isMatched = true)

  private def assertNotMatchRule(settings: ProxyAuthRule.Settings, header: Header) =
    assertRule(settings, header, isMatched = false)

  private def assertRule(settings: ProxyAuthRule.Settings, header: Header, isMatched: Boolean) = {
    val rule = new ProxyAuthRule(settings)
    val requestContext = mock[RequestContext]
    val blockContext = mock[BlockContext]
    val newBlockContext = mock[BlockContext]
    (requestContext.headers _).expects().returning(Set(header))
    if(isMatched) (blockContext.withLoggedUser _).expects(LoggedUser(Id(header.value.value))).returning(newBlockContext)
    rule.check(requestContext, blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(newBlockContext)
      else Rejected
    }
  }
}

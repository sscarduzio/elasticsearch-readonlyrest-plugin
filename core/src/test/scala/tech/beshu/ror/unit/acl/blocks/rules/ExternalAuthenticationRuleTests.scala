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

import eu.timepit.refined.auto._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralNonIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.definitions.ExternalAuthenticationService
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.ExternalAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.ExternalAuthenticationRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.User.Id
import tech.beshu.ror.accesscontrol.domain.{Credentials, PlainTextSecret, User}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.utils.TestsUtils.basicAuthHeader
import tech.beshu.ror.utils.UserIdEq

class ExternalAuthenticationRuleTests extends AnyWordSpec with MockFactory {

  // todo: impersonation tests
  "An ExternalAuthenticationRule" should {
    "match" when {
      "external authentication service returns true" in {
        val baHeader = basicAuthHeader("user:pass")
        val externalAuthenticationService = mock[ExternalAuthenticationService]
        (externalAuthenticationService.authenticate _)
          .expects(where { credentials: Credentials =>
            credentials.user === User.Id("user") && credentials.secret == PlainTextSecret("pass")
          })
          .returning(Task.now(true))

        val requestContext = mock[RequestContext]
        (requestContext.id _).expects().returning(RequestContext.Id("1")).anyNumberOfTimes()
        (requestContext.headers _).expects().returning(Set(baHeader)).anyNumberOfTimes()

        val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty)

        val rule = new ExternalAuthenticationRule(
          Settings(externalAuthenticationService),
          Impersonation.Disabled,
          UserIdEq.caseSensitive
        )
        rule.check(blockContext).runSyncStep shouldBe Right(RuleResult.Fulfilled(
          GeneralNonIndexRequestBlockContext(
            requestContext,
            UserMetadata.empty.withLoggedUser(DirectlyLoggedUser(Id("user"))),
            Set.empty,
            List.empty
          )
        ))
      }
    }
    "not match" when {
      "external authentication service returns false" in {
        val baHeader = basicAuthHeader("user:pass")
        val externalAuthenticationService = mock[ExternalAuthenticationService]
        (externalAuthenticationService.authenticate _)
          .expects(where { credentials: Credentials =>
            credentials.user === User.Id("user") && credentials.secret == PlainTextSecret("pass")
          })
          .returning(Task.now(false))

        val requestContext = mock[RequestContext]
        (requestContext.id _).expects().returning(RequestContext.Id("1")).anyNumberOfTimes()
        (requestContext.headers _).expects().returning(Set(baHeader)).anyNumberOfTimes()

        val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty)

        val rule = new ExternalAuthenticationRule(
          Settings(externalAuthenticationService),
          Impersonation.Disabled,
          UserIdEq.caseSensitive
        )
        rule.check(blockContext).runSyncStep shouldBe Right(RuleResult.Rejected())
      }
    }
  }

}

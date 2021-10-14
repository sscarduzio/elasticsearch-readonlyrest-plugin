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
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralNonIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.base.BasicAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult
import tech.beshu.ror.accesscontrol.domain.LoggedUser.DirectlyLoggedUser
import tech.beshu.ror.accesscontrol.domain.User.Id
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.utils.TestsUtils.basicAuthHeader

trait BasicAuthenticationTestTemplate extends AnyWordSpec with MockFactory {

  protected def ruleName: String

  protected def rule: BasicAuthenticationRule[_]

  s"An $ruleName" should {
    "match" when {
      "basic auth header contains configured in rule's settings value" in {
        val requestContext = mock[RequestContext]
        (requestContext.id _).expects().returning(RequestContext.Id("1"))
        (requestContext.headers _).expects().returning(Set(basicAuthHeader("logstash:logstash"))).twice()
        val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty)
        rule.check(blockContext).runSyncStep shouldBe Right(RuleResult.Fulfilled(
          GeneralNonIndexRequestBlockContext(
            requestContext,
            UserMetadata.empty.withLoggedUser(DirectlyLoggedUser(Id("logstash"))),
            Set.empty,
            List.empty
          )
        ))
      }
    }

    "not match" when {
      "basic auth header contains not configured in rule's settings value" in {
        val requestContext = mock[RequestContext]
        (requestContext.id _).expects().returning(RequestContext.Id("1"))
        (requestContext.headers _).expects().returning(Set(basicAuthHeader("logstash:nologstash"))).twice()
        val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty)
        rule.check(blockContext).runSyncStep shouldBe Right(RuleResult.Rejected())
      }
      "basic auth header is absent" in {
        val requestContext = mock[RequestContext]
        (requestContext.id _).expects().returning(RequestContext.Id("1"))
        (requestContext.headers _).expects().returning(Set.empty).twice()
        val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty)
        rule.check(blockContext).runSyncStep shouldBe Right(RuleResult.Rejected())
      }
    }
  }

}
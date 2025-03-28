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
package tech.beshu.ror.unit.acl.blocks.rules.auth

import cats.data.NonEmptySet
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralNonIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.http.ApiKeysRule
import tech.beshu.ror.accesscontrol.domain.{ApiKey, Header, UriPath}
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.accesscontrol.request.{RequestContext, RestRequest}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*

class ApiKeysRuleTests extends AnyWordSpec with MockFactory {

  "An ApiKeysRule" should {
    "match" when {
      "x-api-key header contains defined in settings value" in {
        assertMatchRule(
          configuredApiKeys = NonEmptySet.of(ApiKey("1234567890")),
          requestHeaders = Set(headerFrom("X-Api-Key" -> "1234567890"))
        )
      }
    }

    "not match" when {
      "x-api-key header contains not defined in settings value" in {
        assertNotMatchRule(
          configuredApiKeys = NonEmptySet.of(ApiKey("1234567890")),
          requestHeaders = Set(headerFrom("X-Api-Key" -> "x"))
        )
      }
      "x-api-key header is absent" in {
        assertNotMatchRule(
          configuredApiKeys = NonEmptySet.of(ApiKey("1234567890")),
          requestHeaders = Set.empty
        )
      }
    }
  }

  private def assertMatchRule(configuredApiKeys: NonEmptySet[ApiKey],
                              requestHeaders: Set[Header]) =
    assertRule(configuredApiKeys, requestHeaders, isMatched = true)

  private def assertNotMatchRule(configuredApiKeys: NonEmptySet[ApiKey],
                                 requestHeaders: Set[Header]) =
    assertRule(configuredApiKeys, requestHeaders, isMatched = false)

  private def assertRule(configuredApiKeys: NonEmptySet[ApiKey],
                         requestHeaders: Set[Header],
                         isMatched: Boolean) = {
    val rule = new ApiKeysRule(ApiKeysRule.Settings(configuredApiKeys))
    val restRequest = mock[RestRequest]
    (() => restRequest.allHeaders).expects().returning(requestHeaders)
    (() => restRequest.path).expects().returning(UriPath.from("/_cat/indices"))
    val requestContext = mock[RequestContext]
    (() => requestContext.restRequest).expects().returning(restRequest).anyNumberOfTimes()
    val blockContext = GeneralNonIndexRequestBlockContext(
      requestContext = requestContext,
      userMetadata = UserMetadata.empty,
      responseHeaders = Set.empty,
      responseTransformations = List.empty
    )
    rule.check(blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(blockContext)
      else Rejected()
    }
  }
}

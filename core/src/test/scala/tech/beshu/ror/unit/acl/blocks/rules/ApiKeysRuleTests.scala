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
import eu.timepit.refined.types.string.NonEmptyString
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.WordSpec
import tech.beshu.ror.utils.TestsUtils._
import tech.beshu.ror.accesscontrol.domain.ApiKey
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralNonIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.ApiKeysRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.request.RequestContext

class ApiKeysRuleTests extends WordSpec with MockFactory {

  private val rule = new ApiKeysRule(ApiKeysRule.Settings(NonEmptySet.of(ApiKey(NonEmptyString.unsafeFrom("1234567890")))))

  "An ApiKeysRule" should {
    "match" when {
      "x-api-key header contains defined in settings value" in {
        val requestContext = mock[RequestContext]
        val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, Set.empty)
        (requestContext.headers _).expects().returning(Set(headerFrom("X-Api-Key" -> "1234567890")))
        rule.check(blockContext).runSyncStep shouldBe Right(Fulfilled(blockContext))
      }
    }

    "not match" when {
      "x-api-key header contains not defined in settings value" in {
        val requestContext = mock[RequestContext]
        val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, Set.empty)
        (requestContext.headers _).expects().returning(Set(headerFrom("X-Api-Key" -> "x")))
        rule.check(blockContext).runSyncStep shouldBe Right(Rejected())
      }
      "x-api-key header is absent" in {
        val requestContext = mock[RequestContext]
        val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, Set.empty)
        (requestContext.headers _).expects().returning(Set.empty)
        rule.check(blockContext).runSyncStep shouldBe Right(Rejected())
      }
    }
  }
}

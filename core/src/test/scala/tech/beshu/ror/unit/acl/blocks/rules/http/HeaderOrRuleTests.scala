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
package tech.beshu.ror.unit.acl.blocks.rules.http

import cats.data.NonEmptySet
import eu.timepit.refined.auto._
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers._
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralNonIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.http.HeadersOrRule
import tech.beshu.ror.accesscontrol.domain.{AccessRequirement, Header, UriPath}
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.utils.TestsUtils.{requiredHeaderFrom, _}

class HeaderOrRuleTests extends AnyWordSpec with MockFactory {

  "A HeadersAndRule" should {
    "match" when {
      "one header was configured and the header was passed with request" in {
        assertMatchRule(
          configuredHeaders = NonEmptySet.of(requiredHeaderFrom("hkey1" -> "hvalue1")),
          requestHeaders = Set(headerFrom("hkey1" -> "hvalue1"))
        )
      }
      "two headers were configured and only one was passed with request" in {
        assertMatchRule(
          configuredHeaders = NonEmptySet.of(
            requiredHeaderFrom("hkey1" -> "hvalue1"),
            requiredHeaderFrom("hkey2" -> "hvalue2")
          ),
          requestHeaders = Set(headerFrom("hkey1" -> "hvalue1"))
        )
      }
      "two headers with same name, but different values were configured and one of them was passed with request" in {
        assertMatchRule(
          configuredHeaders = NonEmptySet.of(
            requiredHeaderFrom("hkey" -> "hvalue1"),
            requiredHeaderFrom("hkey" -> "hvalue2")
          ),
          requestHeaders = Set(headerFrom("hkey" -> "hvalue2"))
        )
      }
      "configured header has wildcard in value" in {
        assertMatchRule(
          configuredHeaders = NonEmptySet.of(requiredHeaderFrom("hkey" -> "hvalue*")),
          requestHeaders = Set(
            headerFrom("hkey" -> "hvalue333"),
            headerFrom("hkey" -> "different")
          )
        )
      }
      "first configured header is forbidden, second is required" when {
        "forbidden header was sent and required header was sent" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(
              requiredHeaderFrom("hkey1" -> "*"),
              forbiddenHeaderFrom("hkey2" -> "*")
            ),
            requestHeaders = Set(
              headerFrom("hkey1" -> "test"),
              headerFrom("hkey2" -> "test")
            )
          )
        }
        "forbidden header wasn't sent and required header wasn't sent" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(
              requiredHeaderFrom("hkey1" -> "*"),
              forbiddenHeaderFrom("hkey2" -> "*")
            ),
            requestHeaders = Set.empty
          )
        }
        "forbidden header wasn't sent and required header was sent" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(
              forbiddenHeaderFrom("hkey1" -> "value1"),
              requiredHeaderFrom("hkey2" -> "*")
            ),
            requestHeaders = Set(
              headerFrom("hkey2" -> "value1"),
              headerFrom("hkey4" -> "hvalue3")
            )
          )
        }
      }
    }
    "not match" when {
      "one header was configured and no headers was passed with request" in {
        assertNotMatchRule(
          configuredHeaders = NonEmptySet.of(requiredHeaderFrom("hkey1" -> "hvalue1")),
          requestHeaders = Set.empty
        )
      }
      "one header was configured and it wasn't passed with request headers" in {
        assertNotMatchRule(
          configuredHeaders = NonEmptySet.of(requiredHeaderFrom("hkey1" -> "hvalue1")),
          requestHeaders = Set(
            headerFrom("hkey2" -> "hvalue2"),
            headerFrom("hkey3" -> "hvalue3")
          )
        )
      }
      "two headers were configured and none of them was passed with the request" in {
        assertNotMatchRule(
          configuredHeaders = NonEmptySet.of(
            requiredHeaderFrom("hkey1" -> "*"),
            requiredHeaderFrom("hkey2" -> "*")
          ),
          requestHeaders = Set(
            headerFrom("hkey3" -> "hvalue2"),
            headerFrom("hkey4" -> "hvalue3")
          )
        )
      }
      "two configured headers are forbidden, and both was sent" in {
        assertNotMatchRule(
          configuredHeaders = NonEmptySet.of(
            forbiddenHeaderFrom("hkey1" -> "*"),
            forbiddenHeaderFrom("hkey2" -> "*")
          ),
          requestHeaders = Set(
            headerFrom("hkey1" -> "hvalue1"),
            headerFrom("hkey2" -> "hvalue2")
          )
        )
      }
      "first configured header is forbidden, second is required" when {
        "forbidden header was sent and required header wasn't sent" in {
          assertNotMatchRule(
            configuredHeaders = NonEmptySet.of(
              forbiddenHeaderFrom("hkey1" -> "value1"),
              requiredHeaderFrom("hkey2" -> "*")
            ),
            requestHeaders = Set(
              headerFrom("hkey1" -> "value1"),
              headerFrom("hkey3" -> "hvalue3")
            )
          )
        }
      }
    }
  }

  private def assertMatchRule(configuredHeaders: NonEmptySet[AccessRequirement[Header]],
                              requestHeaders: Set[Header]) =
    assertRule(configuredHeaders, requestHeaders, isMatched = true)

  private def assertNotMatchRule(configuredHeaders: NonEmptySet[AccessRequirement[Header]],
                                 requestHeaders: Set[Header]) =
    assertRule(configuredHeaders, requestHeaders, isMatched = false)

  private def assertRule(configuredHeaders: NonEmptySet[AccessRequirement[Header]],
                         requestHeaders: Set[Header],
                         isMatched: Boolean) = {
    val rule = new HeadersOrRule(HeadersOrRule.Settings(configuredHeaders))
    val requestContext = mock[RequestContext]
    (() => requestContext.headers).expects().returning(requestHeaders)
    (() => requestContext.id).expects().returning(RequestContext.Id("1")).anyNumberOfTimes()
    (() => requestContext.uriPath).expects().returning(UriPath("/_cat/indices"))
    val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty)
    rule.check(blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(blockContext)
      else Rejected()
    }
  }
}

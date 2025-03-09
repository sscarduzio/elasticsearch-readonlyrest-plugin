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
import monix.execution.Scheduler.Implicits.global
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralNonIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.metadata.UserMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.http.HeadersAndRule
import tech.beshu.ror.accesscontrol.domain.{AccessRequirement, Header, UriPath}
import tech.beshu.ror.accesscontrol.orders.*
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*

class HeadersAndRuleTests extends AnyWordSpec with MockFactory {

  "A HeadersAndRule" should {
    "match" when {
      "one header is configured and one header is passed with request" when {
        "there is no other additional header in request" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(requiredHeaderFrom("hkey" -> "hvalue")),
            requestHeaders = Set(headerFrom("hkey" -> "hvalue"))
          )
        }
        "value of the configured header has wildcard" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(requiredHeaderFrom("hkey" -> "h*")),
            requestHeaders = Set(headerFrom("hkey" -> "hvalue"))
          )
        }
        "request header has name is case insensitive" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(requiredHeaderFrom("hkey" -> "hvalue")),
            requestHeaders = Set(headerFrom("Hkey" -> "hvalue"))
          )
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(requiredHeaderFrom("Hkey" -> "hvalue")),
            requestHeaders = Set(headerFrom("hkey" -> "hvalue"))
          )
        }
        "there are additional headers in request" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(requiredHeaderFrom("hkey" -> "hvalue")),
            requestHeaders = Set(
              headerFrom("Hkey" -> "hvalue"),
              headerFrom("other" -> "header")
            )
          )
        }
        "configured header value has wildcard character in its value" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(requiredHeaderFrom("hkey" -> "h*")),
            requestHeaders = Set(headerFrom("Hkey" -> "hvalue"))
          )
        }
        "configured header value has a colon in its value" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(requiredHeaderFrom("hkey" -> "x:y")),
            requestHeaders = Set(headerFrom("hkey" -> "x:y"))
          )
        }
        "any value of the header will be accepted" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(requiredHeaderFrom("hkey" -> "*")),
            requestHeaders = Set(headerFrom("hkey" -> "xyz"))
          )
        }
        "not allowed header syntax is used" when {
          "there is one request header" in {
            assertMatchRule(
              configuredHeaders = NonEmptySet.of(forbiddenHeaderFrom("hkey" -> "*")),
              requestHeaders = Set(headerFrom("hkey2" -> "xyz"))
            )
          }
          "there is none request header" in {
            assertMatchRule(
              configuredHeaders = NonEmptySet.of(forbiddenHeaderFrom("hkey" -> "*")),
              requestHeaders = Set.empty
            )
          }
        }
        "not allowed header and value syntax is used" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(forbiddenHeaderFrom("hkey" -> "test")),
            requestHeaders = Set(headerFrom("hkey" -> "other"))
          )
        }
      }
      "two headers are configured and two headers are passed with request" when {
        "their names are different" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(
              requiredHeaderFrom("hkey1" -> "hvalue2"),
              requiredHeaderFrom("hkey2" -> "hvalue2")
            ),
            requestHeaders = Set(
              headerFrom("hkey1" -> "hvalue2"),
              headerFrom("hkey2" -> "hvalue2")
            )
          )
        }
        "their names are the same" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(
              requiredHeaderFrom("hkey" -> "hvalue1"),
              requiredHeaderFrom("hkey" -> "hvalue2")
            ),
            requestHeaders = Set(
              headerFrom("hkey" -> "hvalue2"),
              headerFrom("hkey" -> "hvalue1")
            )
          )
        }
        "one header is not allowed" in {
          assertMatchRule(
            configuredHeaders = NonEmptySet.of(
              forbiddenHeaderFrom("hkey1" -> "*"),
              requiredHeaderFrom("hkey2" -> "hvalue2")
            ),
            requestHeaders = Set(
              headerFrom("hkey2" -> "hvalue2"),
              headerFrom("hkey3" -> "hvalue3")
            )
          )
        }
      }
    }
    "not match" when {
      "two headers are configured, but only one is passed" in {
        assertNotMatchRule(
          configuredHeaders = NonEmptySet.of(
            requiredHeaderFrom("hkey1" -> "hvalue1"),
            requiredHeaderFrom("hkey2" -> "hvalue2")
          ),
          requestHeaders = Set(headerFrom("hkey1" -> "hvalue1"))
        )
      }
      "one header is configured, but request contains one, different header" in {
        assertNotMatchRule(
          configuredHeaders = NonEmptySet.of(requiredHeaderFrom("hkey1" -> "hvalue1")),
          requestHeaders = Set(headerFrom("hkey2" -> "hvalue2"))
        )
      }
      "one header is configure, but request has no headers" in {
        assertNotMatchRule(
          configuredHeaders = NonEmptySet.of(requiredHeaderFrom("hkey1" -> "hvalue1")),
          requestHeaders = Set.empty
        )
      }
      "request header has name is case sensitive" in {
        assertNotMatchRule(
          configuredHeaders = NonEmptySet.of(requiredHeaderFrom("hkey" -> "hvalue")),
          requestHeaders = Set(headerFrom("hkey" -> "HValue"))
        )
        assertNotMatchRule(
          configuredHeaders = NonEmptySet.of(requiredHeaderFrom("hkey" -> "HValue")),
          requestHeaders = Set(headerFrom("hkey" -> "hvalue"))
        )
      }
      "value of the sent header contains `*` but expected value is different" in {
        assertNotMatchRule(
          configuredHeaders = NonEmptySet.of(requiredHeaderFrom("hkey" -> "any")),
          requestHeaders = Set(headerFrom("hkey" -> "*"))
        )
      }
      "at least one not allowed header is present" in {
        assertNotMatchRule(
          configuredHeaders = NonEmptySet.of(forbiddenHeaderFrom("hkey" -> "*")),
          requestHeaders = Set(headerFrom("hkey" -> "test"))
        )
      }
      "at least one not allowed header and value is present" in {
        assertNotMatchRule(
          configuredHeaders = NonEmptySet.of(
            forbiddenHeaderFrom("hkey" -> "test"),
            forbiddenHeaderFrom("hkey2" -> "test"),
          ),
          requestHeaders = Set(headerFrom("hkey" -> "test"))
        )
      }
    }
  }

  private def assertMatchRule(configuredHeaders: NonEmptySet[AccessRequirement[Header]], requestHeaders: Set[Header]) =
    assertRule(configuredHeaders, requestHeaders, isMatched = true)

  private def assertNotMatchRule(configuredHeaders: NonEmptySet[AccessRequirement[Header]], requestHeaders: Set[Header]) =
    assertRule(configuredHeaders, requestHeaders, isMatched = false)

  private def assertRule(configuredHeaders: NonEmptySet[AccessRequirement[Header]], requestHeaders: Set[Header], isMatched: Boolean) = {
    val rule = new HeadersAndRule(HeadersAndRule.Settings(configuredHeaders))
    val requestContext = mock[RequestContext]
    (() => requestContext.restRequest.allHeaders).expects().returning(requestHeaders)
    (() => requestContext.id).expects().returning(RequestContext.Id.fromString("1")).anyNumberOfTimes()
    (() => requestContext.restRequest.path).expects().returning(UriPath.from("/_cat/indices"))
    val blockContext = GeneralNonIndexRequestBlockContext(requestContext, UserMetadata.empty, Set.empty, List.empty)
    rule.check(blockContext).runSyncStep shouldBe Right {
      if (isMatched) Fulfilled(blockContext)
      else Rejected()
    }
  }
}
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
package tech.beshu.ror.unit.acl.request

import org.scalamock.scalatest.MockFactory
import org.scalatest.Matchers._
import org.scalatest.{Assertion, Inside, WordSpec}
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.accesscontrol.request.{EsRequestContext, RequestContext, RequestInfoShim}
import tech.beshu.ror.utils.TestsUtils.header

import scala.util.{Success, Try}

class EsRequestContextTests extends WordSpec with MockFactory with Inside {

  import EsRequestContextTests.CustomHeaderMatcher

  "Headers" should {
    "be created from raw values" when {
      "these are non empty in names and values" in {
        val requestInfo = mockHeaders(Map(
          "header1" -> Set("value1"),
          "header2" -> Set("value2")
        ))

        EsRequestContext.from(requestInfo) hasHeaders {
          Set(
            header("header1", "value1"),
            header("header2", "value2")
          )
        }
      }
      "multiple values for one header name are used" in {
        val requestInfo = mockHeaders(Map(
          "header1" -> Set("value1a", "value1b"),
          "header2" -> Set("value2")
        ))

        EsRequestContext.from(requestInfo) hasHeaders {
          Set(
            header("header1", "value1a"),
            header("header1", "value1b"),
            header("header2", "value2")
          )
        }
      }
      "empty header names are skipped" in {
        val requestInfo = mockHeaders(Map(
          "header1" -> Set("value1"),
          "" -> Set("value2")
        ))

        EsRequestContext.from(requestInfo) hasHeaders {
          Set(
            header("header1", "value1")
          )
        }
      }
      "empty header values are skipped" in {
        val requestInfo = mockHeaders(Map(
          "header1" -> Set("value1"),
          "header2" -> Set("")
        ))

        EsRequestContext.from(requestInfo) hasHeaders {
          Set(
            header("header1", "value1")
          )
        }
      }
      "authorization header is recognized" when {
        "it is used with one value only" in {
          val requestInfo = mockHeaders(Map(
            "header1" -> Set("value1"),
            "Authorization" -> Set("Basic a2liYW5hOmtpYmFuYQ==")
          ))

          EsRequestContext.from(requestInfo) hasHeaders {
            Set(
              header("header1", "value1"),
              header("Authorization", "Basic a2liYW5hOmtpYmFuYQ=="),
            )
          }
        }
        "it's used with multiple values" in {
          val requestInfo = mockHeaders(Map(
            "header1" -> Set("value1"),
            "Authorization" -> Set("Basic a2liYW5hOmtpYmFuYQ==", "Bearer mF_9.B5f-4.1JqM")
          ))

          EsRequestContext.from(requestInfo) hasHeaders {
            Set(
              header("header1", "value1"),
              header("Authorization", "Basic a2liYW5hOmtpYmFuYQ=="),
              header("Authorization", "Bearer mF_9.B5f-4.1JqM")
            )
          }
        }
        "inside its value there is ror_metadata json with inner, artificial headers" in {
          val requestInfo = mockHeaders(Map(
            "header1" -> Set("value1"),
            "Authorization" -> Set("Basic a2liYW5hOmtpYmFuYQ==, ror_metadata=eyJoZWFkZXJzIjpbIngtcm9yLWN1cnJlbnQtZ3JvdXA6ZzEiLCAia2V5OnZhbHVlIl19")
          ))

          EsRequestContext.from(requestInfo) hasHeaders {
            Set(
              header("header1", "value1"),
              header("Authorization", "Basic a2liYW5hOmtpYmFuYQ=="),
              header("x-ror-current-group", "g1"),
              header("key", "value")
            )
          }
        }
        "artificial header is passed and real header is also passed, the latter one is taken" in {
          val requestInfo = mockHeaders(Map(
            "header1" -> Set("value1"),
            "Authorization" -> Set("Basic a2liYW5hOmtpYmFuYQ==, ror_metadata=eyJoZWFkZXJzIjpbIngtcm9yLWN1cnJlbnQtZ3JvdXA6ZzEiLCAia2V5OnZhbHVlIl19"),
            "x-ror-current-group" -> Set("g2")
          ))

          EsRequestContext.from(requestInfo) hasHeaders {
            Set(
              header("header1", "value1"),
              header("Authorization", "Basic a2liYW5hOmtpYmFuYQ=="),
              header("x-ror-current-group", "g2"),
              header("key", "value")
            )
          }
        }
      }
    }
  }

  private def mockHeaders(values: Map[String, Set[String]]) = {
    val requestInfo = mock[RequestInfoShim]
    (requestInfo.extractRequestHeaders _).expects().once().returning(values)
    requestInfo
  }

}

object EsRequestContextTests {

  private implicit class CustomHeaderMatcher(val requestContext: Try[RequestContext]) extends Inside {
    def hasHeaders(set: Set[Header]): Assertion = {
      inside(requestContext) { case Success(context) =>
        context.headers should be(set)
      }
    }
  }
}

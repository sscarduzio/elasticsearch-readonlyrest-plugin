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
package tech.beshu.ror.unit.acl.domain

import eu.timepit.refined.types.string.NonEmptyString
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.Header.AuthorizationValueError.*
import tech.beshu.ror.accesscontrol.domain.{Address, Header}
import tech.beshu.ror.accesscontrol.request.RestRequest.*
import tech.beshu.ror.mocks.MockRestRequest
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.testRequestId
import tech.beshu.ror.utils.uniquelist.UniqueList

import java.nio.charset.StandardCharsets
import java.util.Base64

class HeaderTests extends AnyWordSpec with Matchers {

  "Header.Name" should {
    "ignore case in equality and in the hash code" in {
      val upperCased = nameOf("X-Forwarded-User")
      val lowerCased = nameOf("x-forwarded-user")

      upperCased should be(lowerCased)
      upperCased.hashCode should be(lowerCased.hashCode)
      scala.collection.immutable.Set(upperCased, lowerCased) should have size 1
    }
    "keep the spelling which came from the wire" in {
      nameOf("X-Forwarded-User").value.value should be("X-Forwarded-User")
    }
    "not equal a name which differs by more than case" in {
      nameOf("X-Forwarded-User") should not be nameOf("X-Forwarded-Users")
    }
    "order by the lower-cased name" in {
      List(nameOf("X-Beta"), nameOf("x-alpha"), nameOf("X-Gamma"))
        .sorted(Header.Name.orderName.toOrdering)
        .map(_.value.value) should be(List("x-alpha", "X-Beta", "X-Gamma"))
    }
  }

  "Header.fromRawHeaders" when {
    "the Authorization header carries ror_metadata" should {
      "keep a ror_metadata header which does not clash with a real header" in {
        val headers = headersFrom(rorMetadataHeaders = "x-ror-current-group:group1")

        valuesOf(headers, "x-ror-current-group") should be(List("group1"))
      }
      "keep one header when a ror_metadata header repeats a real header" in {
        val headers = headersFrom(
          realHeaders = Map("X-Forwarded-User" -> "bob"),
          rorMetadataHeaders = "X-Forwarded-User:bob"
        )

        valuesOf(headers, "x-forwarded-user") should be(List("bob"))
      }
      "keep one set of values when a multi-value header arrives on both channels" in {
        val headers = headersFromMultiValued(
          realHeaders = Map("X-Forwarded-For" -> List("10.0.0.1", "10.0.0.2")),
          rorMetadataHeaders = List("X-Forwarded-For:10.0.0.2", "X-Forwarded-For:10.0.0.1")
        )

        valuesOf(headers, "x-forwarded-for").sorted should be(List("10.0.0.1", "10.0.0.2"))
      }
      "keep the Authorization header which comes before the ror_metadata part" in {
        val headers = headersFrom(rorMetadataHeaders = "x-ror-current-group:group1")

        valuesOf(headers, "Authorization") should be(List("Basic dXNlcjpwYXNz"))
      }
      "reject a ror_metadata header which has another value than the real header" in {
        errorFrom(
          realHeaders = Map("X-Forwarded-User" -> "bob"),
          rorMetadataHeaders = "X-Forwarded-User:admin"
        ) should be(HeaderValuesConflict(Header.Name.xForwardedUser, 1, 1))
      }
      "report the count of values of each channel in the conflict" in {
        errorFrom(
          realHeaders = Map("X-Forwarded-For" -> List("10.0.0.1", "10.0.0.2")),
          rorMetadataHeaders = List("X-Forwarded-For:10.0.0.3")
        ) should be(HeaderValuesConflict(Header.Name.xForwardedFor, 2, 1))
      }
      "reject a clashing ror_metadata header written in a different case" in {
        val caseVariants = List(
          "x-forwarded-user",
          "X-FORWARDED-USER",
          "x-Forwarded-User",
          "X-forwarded-user",
          "X-Forwarded-user",
          "x-forwarded-User"
        )

        caseVariants.foreach { name =>
          withClue(s"ror_metadata header name: $name") {
            errorFrom(
              realHeaders = Map("X-Forwarded-User" -> "bob"),
              rorMetadataHeaders = s"$name:admin"
            ) should be(HeaderValuesConflict(Header.Name.xForwardedUser, 1, 1))
          }
        }
      }
      "reject a ror_metadata header which has no colon" in {
        errorFrom(realHeaders = Map.empty, rorMetadataHeaders = "x-ror-current-group") should be(
          InvalidHeaderFormat("x-ror-current-group")
        )
      }
      "reject a ror_metadata part which is not Base64" in {
        rawHeadersOf(
          Map("Authorization" -> List("Basic dXNlcjpwYXNz, ror_metadata=!!!not-base64!!!"))
        ) should be(Left(RorMetadataInvalidFormat("!!!not-base64!!!", "Decoding Base64 failed")))
      }
      "reject a ror_metadata part which is not the expected JSON" in {
        val notExpectedJson = base64Of("""{"something":"else"}""")

        rawHeadersOf(
          Map("Authorization" -> List(s"Basic dXNlcjpwYXNz, ror_metadata=$notExpectedJson"))
        ) should be(Left(RorMetadataInvalidFormat(notExpectedJson, "Parsing JSON failed")))
      }
      "reject an Authorization header which holds nothing but the ror_metadata part" in {
        rawHeadersOf(
          Map("Authorization" -> List(s"ror_metadata=${rorMetadataOf(List("x-ror-current-group:group1"))}"))
        ) should be(Left(EmptyAuthorizationValue))
      }
    }
    "the same header arrives under two case spellings" should {
      // Netty keeps both spellings in `names()` and its `getAll` is case-insensitive, so Elasticsearch
      // hands ROR the same value list under each spelling.
      "not multiply the headers" in {
        val headers = headersOf(
          Map(
            "X-Forwarded-User" -> List("bob"),
            "x-forwarded-user" -> List("bob")
          )
        )

        valuesOf(headers, "x-forwarded-user") should be(List("bob"))
      }
    }
    "one name arrives with two values" should {
      "keep the values in the order they arrived" in {
        valuesOf(headersOf(Map("X-Forwarded-For" -> List("10.0.0.1", "10.0.0.2"))), "x-forwarded-for") should be(
          List("10.0.0.1", "10.0.0.2")
        )
      }
      "keep the reversed order when the values arrive reversed" in {
        valuesOf(headersOf(Map("X-Forwarded-For" -> List("10.0.0.2", "10.0.0.1"))), "x-forwarded-for") should be(
          List("10.0.0.2", "10.0.0.1")
        )
      }
    }
    "a header has no name or no value" should {
      "drop the header with the empty name" in {
        headersOf(Map("" -> List("bob"), "X-Forwarded-User" -> List("bob"))).toList
          .map(_.name.value.value) should be(List("X-Forwarded-User"))
      }
      "drop the empty value and keep each other value of the name" in {
        valuesOf(headersOf(Map("X-Forwarded-For" -> List("", "10.0.0.1"))), "x-forwarded-for") should be(
          List("10.0.0.1")
        )
      }
      "drop the header which holds no value at all" in {
        headersOf(Map("X-Forwarded-User" -> List.empty)) should be(empty)
      }
    }
    "the raw headers come from Elasticsearch as a Java map" should {
      "read the header without case" in {
        val rawHeaders = new java.util.HashMap[String, java.util.List[String]]()
        rawHeaders.put("X-ROR-KBN-LICENSE-TYPE", java.util.List.of("enterprise"))

        Header.fromRawHeaders(rawHeaders).map(valuesOf(_, "x-ror-kbn-license-type")) should be(
          Right(List("enterprise"))
        )
      }
    }
  }

  "RestRequest.xForwardedForHeaderValue" should {
    "return the first X-Forwarded-For entry in wire order when the header arrives twice" in {
      val request = MockRestRequest(allHeaders =
        headersOf(
          Map("X-Forwarded-For" -> List("203.0.113.10", "10.0.0.1"))
        )
      )

      request.xForwardedForHeaderValue should be(Address.from("203.0.113.10"))
    }
    "return the first X-Forwarded-For entry when the two values arrive in the other order" in {
      val request = MockRestRequest(allHeaders =
        headersOf(
          Map("X-Forwarded-For" -> List("10.0.0.1", "203.0.113.10"))
        )
      )

      request.xForwardedForHeaderValue should be(Address.from("10.0.0.1"))
    }
  }

  "Header.findSingleHeader" should {
    "return no header when the name is absent" in {
      Header.findSingleHeader(nameOf("X-Api-Key"), in = setOf("X-Forwarded-User" -> "bob")) should be(Right(None))
    }
    "return no header when nothing is given" in {
      Header.findSingleHeader(nameOf("X-Api-Key"), in = setOf()) should be(Right(None))
    }
    "return the header when the name holds one value" in {
      Header.findSingleHeader(nameOf("X-Api-Key"), in = setOf("X-Api-Key" -> "key1")) should be(
        Right(Some(headerOf("X-Api-Key", "key1")))
      )
    }
    "return the header when the name of the search differs by case" in {
      Header.findSingleHeader(nameOf("x-api-key"), in = setOf("X-Api-Key" -> "key1")) should be(
        Right(Some(headerOf("X-Api-Key", "key1")))
      )
    }
    "return the header when the same value repeats under the name" in {
      Header.findSingleHeader(
        nameOf("X-Api-Key"),
        in = setOf("X-Api-Key" -> "key1", "x-api-key" -> "key1")
      ) should be(Right(Some(headerOf("X-Api-Key", "key1"))))
    }
    "reject the name when it holds two different values" in {
      Header.findSingleHeader(
        nameOf("X-Api-Key"),
        in = setOf("X-Api-Key" -> "key1", "X-Api-Key" -> "key2")
      ) should be(Left(Header.AmbiguousHeader(nameOf("X-Api-Key"))))
    }
    "reject the name when the two values arrive under two case spellings" in {
      Header.findSingleHeader(
        nameOf("X-Api-Key"),
        in = setOf("X-Api-Key" -> "key1", "x-api-key" -> "key2")
      ) should be(Left(Header.AmbiguousHeader(nameOf("X-Api-Key"))))
    }
  }

  "Header.singleHeaderOrNone" should {
    "return the header when the name holds one value" in {
      Header.singleHeaderOrNone(nameOf("X-Api-Key"), in = setOf("X-Api-Key" -> "key1")) should be(
        Some(headerOf("X-Api-Key", "key1"))
      )
    }
    "return no header when the name is absent" in {
      Header.singleHeaderOrNone(nameOf("X-Api-Key"), in = setOf("X-Forwarded-User" -> "bob")) should be(None)
    }
    "return no header when the name holds two different values" in {
      Header.singleHeaderOrNone(
        nameOf("X-Api-Key"),
        in = setOf("X-Api-Key" -> "key1", "x-api-key" -> "key2")
      ) should be(None)
    }
  }

  private def nameOf(name: String) = Header.Name(NonEmptyString.unsafeFrom(name))

  private def headerOf(name: String, value: String) = Header(nameOf(name), NonEmptyString.unsafeFrom(value))

  private def setOf(nameAndValues: (String, String)*) =
    UniqueList.from(nameAndValues.map { case (name, value) => headerOf(name, value) })

  private def headersFrom(realHeaders: Map[String, String] = Map.empty, rorMetadataHeaders: String*) = {
    headersFromMultiValued(realHeaders.view.mapValues(List(_)).toMap, rorMetadataHeaders.toList)
  }

  private def headersFromMultiValued(
      realHeaders: Map[String, List[String]],
      rorMetadataHeaders: List[String]
  ) = {
    resultOf(realHeaders, rorMetadataHeaders) match {
      case Right(headers) => headers
      case Left(error)    => fail(s"Cannot create headers: ${error.toString}")
    }
  }

  private def errorFrom(realHeaders: Map[String, String], rorMetadataHeaders: String*): Header.AuthorizationValueError =
    errorFrom(realHeaders.view.mapValues(List(_)).toMap, rorMetadataHeaders.toList)

  private def errorFrom(realHeaders: Map[String, List[String]], rorMetadataHeaders: List[String]) = {
    resultOf(realHeaders, rorMetadataHeaders) match {
      case Left(error)    => error
      case Right(headers) => fail(s"Expected an error, but got headers: ${headers.toString}")
    }
  }

  private def resultOf(realHeaders: Map[String, List[String]], rorMetadataHeaders: List[String]) = {
    rawHeadersOf(
      realHeaders + ("Authorization" -> List(s"Basic dXNlcjpwYXNz, ror_metadata=${rorMetadataOf(rorMetadataHeaders)}"))
    )
  }

  private def rorMetadataOf(rorMetadataHeaders: List[String]) = base64Of(
    ujson.Obj("headers" -> ujson.Arr(rorMetadataHeaders.map(ujson.Str.apply)*)).render()
  )

  private def base64Of(value: String) =
    Base64.getEncoder.encodeToString(value.getBytes(StandardCharsets.UTF_8))

  private def headersOf(rawHeaders: Map[String, List[String]]) = rawHeadersOf(rawHeaders) match {
    case Right(headers) => headers
    case Left(error)    => fail(s"Cannot create headers: ${error.toString}")
  }

  private def rawHeadersOf(rawHeaders: Map[String, List[String]]) = Header.fromRawHeaders(rawHeaders)

  private def valuesOf(headers: UniqueList[Header], name: String) =
    headers.toList
      .filter(_.name == nameOf(name))
      .map(_.value.value)

}

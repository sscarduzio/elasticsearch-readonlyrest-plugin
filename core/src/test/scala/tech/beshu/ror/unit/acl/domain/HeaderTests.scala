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
import org.apache.logging.log4j.Level
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.Header.AuthorizationValueError.*
import tech.beshu.ror.accesscontrol.domain.{Address, Header}
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.mocks.{MockRequestContext, MockRestRequest}
import tech.beshu.ror.utils.LogEventsCapture.captureLogEvents
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

  "Header.fromRawHeaders" should {
    "log one DEBUG line, with at most 10 names, when many headers differ between the request and ror_metadata" in {
      val names = (1 to 200).map(i => s"x-custom-$i").toList
      val events = captureLogEvents(headerLoggerName) {
        headersFromMultiValued(
          realHeaders = names.map(name => name -> List("http")).toMap,
          rorMetadataHeaders = names.map(name => s"$name:meta")
        )
      }
      events.map(_.level) should be(List(Level.DEBUG))
      events.head.message should include("and 190 more")
      names.count(name => events.head.message.contains(s"'$name'")) should be(10)
    }
    "log nothing when the request and ror_metadata hold the same values" in {
      val events = captureLogEvents(headerLoggerName) {
        headersFrom(realHeaders = Map("X-Forwarded-User" -> "bob"), rorMetadataHeaders = "X-Forwarded-User:bob")
      }
      events should be(List.empty)
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
      "keep the real values in wire order when a multi-value header arrives on both channels" in {
        val headers = headersFromMultiValued(
          realHeaders = Map("X-Forwarded-For" -> List("10.0.0.1", "10.0.0.2")) ++ otherRealHeaders,
          rorMetadataHeaders = List("X-Forwarded-For:10.0.0.2", "X-Forwarded-For:10.0.0.1")
        )

        valuesOf(headers, "x-forwarded-for") should be(List("10.0.0.1", "10.0.0.2"))
      }
      "keep the real values in wire order when the wire order is reversed" in {
        val headers = headersFromMultiValued(
          realHeaders = Map("X-Forwarded-For" -> List("10.0.0.2", "10.0.0.1")) ++ otherRealHeaders,
          rorMetadataHeaders = List("X-Forwarded-For:10.0.0.1", "X-Forwarded-For:10.0.0.2")
        )

        valuesOf(headers, "x-forwarded-for") should be(List("10.0.0.2", "10.0.0.1"))
      }
      "keep the Authorization header which comes before the ror_metadata part" in {
        val headers = headersFrom(rorMetadataHeaders = "x-ror-current-group:group1")

        valuesOf(headers, "Authorization") should be(List("Basic dXNlcjpwYXNz"))
      }
      "keep the real header when a ror_metadata header has another value" in {
        val headers = headersFrom(
          realHeaders = Map("X-Forwarded-User" -> "bob"),
          rorMetadataHeaders = "X-Forwarded-User:admin"
        )

        valuesOf(headers, "x-forwarded-user") should be(List("bob"))
      }
      "keep all values of the real header when ror_metadata has other values" in {
        val headers = headersFromMultiValued(
          realHeaders = Map("X-Forwarded-For" -> List("10.0.0.1", "10.0.0.2")),
          rorMetadataHeaders = List("X-Forwarded-For:10.0.0.3")
        )

        valuesOf(headers, "x-forwarded-for") should be(List("10.0.0.1", "10.0.0.2"))
      }
      "keep the real X-Forwarded-For header when ror_metadata has another value" in {
        val headers = headersFrom(
          realHeaders = Map("X-Forwarded-For" -> "10.0.0.1"),
          rorMetadataHeaders = "x-forwarded-for:10.0.0.2"
        )

        valuesOf(headers, "x-forwarded-for") should be(List("10.0.0.1"))
      }
      "keep the real header when the ror_metadata header has the same name in a different case" in {
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
            val headers = headersFrom(
              realHeaders = Map("X-Forwarded-User" -> "bob"),
              rorMetadataHeaders = s"$name:admin"
            )

            valuesOf(headers, "x-forwarded-user") should be(List("bob"))
          }
        }
      }
      "keep a ror_metadata header which the request does not have" in {
        val headers = headersFrom(
          realHeaders = Map("X-Forwarded-For" -> "10.0.0.1"),
          rorMetadataHeaders = "X-Forwarded-User:bob"
        )

        valuesOf(headers, "x-forwarded-user") should be(List("bob"))
        valuesOf(headers, "x-forwarded-for") should be(List("10.0.0.1"))
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
        valuesOf(
          headersOf(Map("X-Forwarded-For" -> List("10.0.0.1", "10.0.0.2")) ++ otherRealHeaders),
          "x-forwarded-for"
        ) should be(List("10.0.0.1", "10.0.0.2"))
      }
      "keep the reversed order when the values arrive reversed" in {
        valuesOf(
          headersOf(Map("X-Forwarded-For" -> List("10.0.0.2", "10.0.0.1")) ++ otherRealHeaders),
          "x-forwarded-for"
        ) should be(List("10.0.0.2", "10.0.0.1"))
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

  "Address.asText" should {
    "show a plain IPv4 address for one host" in {
      Address.from("192.168.0.1").map(_.asText) should be(Some("192.168.0.1"))
    }
    "show the CIDR form for an IPv4 range" in {
      Address.from("10.0.0.0/8").map(_.asText) should be(Some("10.0.0.0/8"))
    }
    "show the CIDR form for an IPv6 range" in {
      Address.from("2001:db8::/48").map(_.asText) should be(Some("2001:db8::/48"))
    }
    "show a host name as it is" in {
      Address.from("es-node-1.example.com").map(_.asText) should be(Some("es-node-1.example.com"))
    }
  }

  "RequestHeaders.xForwardedFor" should {
    "return the first X-Forwarded-For entry in wire order when the header arrives twice" in {
      val requestContext = requestContextWith(
        headersOf(Map("X-Forwarded-For" -> List("203.0.113.10", "10.0.0.1")) ++ otherRealHeaders)
      )

      requestContext.headers.xForwardedFor should be(Address.from("203.0.113.10"))
    }
    "return the first X-Forwarded-For entry when the two values arrive in the other order" in {
      val requestContext = requestContextWith(
        headersOf(Map("X-Forwarded-For" -> List("10.0.0.1", "203.0.113.10")) ++ otherRealHeaders)
      )

      requestContext.headers.xForwardedFor should be(Address.from("10.0.0.1"))
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

  "Header.singleHeaderOrAmbiguity" should {
    "return the header when the name holds one value" in {
      Header.singleHeaderOrAmbiguity(nameOf("X-Api-Key"), in = setOf("X-Api-Key" -> "key1")) should be(
        Right(Some(headerOf("X-Api-Key", "key1")))
      )
    }
    "return no header when the name is absent" in {
      Header.singleHeaderOrAmbiguity(nameOf("X-Api-Key"), in = setOf("X-Forwarded-User" -> "bob")) should be(
        Right(None)
      )
    }
    "return the ambiguity and log one warning when the name holds two different values" in {
      val events = captureLogEvents(headerLoggerName) {
        Header.singleHeaderOrAmbiguity(
          nameOf("X-Api-Key"),
          in = setOf("X-Api-Key" -> "key1", "x-api-key" -> "key2")
        ) should be(Left(Header.AmbiguousHeader(nameOf("X-Api-Key"))))
      }
      events.map(_.level) should be(List(Level.WARN))
    }
  }

  private lazy val headerLoggerName = Header.getClass.getName

  // Scala sets of up to 4 elements keep the insertion order. With more headers, a set loses the wire order.
  private val otherRealHeaders = Map(
    "Host" -> List("localhost:9200"),
    "User-Agent" -> List("curl/8.5.0"),
    "Accept" -> List("*/*"),
    "Accept-Encoding" -> List("gzip"),
    "Connection" -> List("keep-alive"),
    "Content-Type" -> List("application/json")
  )

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

  private def requestContextWith(headers: UniqueList[Header]): RequestContext =
    MockRequestContext.nonIndices.copy(restRequest = MockRestRequest(allHeaders = headers))

  private def valuesOf(headers: UniqueList[Header], name: String) =
    headers.toList
      .filter(_.name == nameOf(name))
      .map(_.value.value)

}

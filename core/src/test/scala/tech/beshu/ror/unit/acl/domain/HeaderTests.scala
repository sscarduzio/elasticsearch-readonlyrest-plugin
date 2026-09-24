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
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.syntax.*

import java.util.Base64

class HeaderTests extends AnyWordSpec with Matchers {

  "Header.Name" should {
    "ignore case in equality and in the hash code" in {
      val upperCased = Header.Name(NonEmptyString.unsafeFrom("X-Forwarded-User"))
      val lowerCased = Header.Name(NonEmptyString.unsafeFrom("x-forwarded-user"))

      upperCased should be(lowerCased)
      upperCased.hashCode should be(lowerCased.hashCode)
      scala.collection.immutable.Set(upperCased, lowerCased) should have size 1
    }
    "keep the spelling which came from the wire" in {
      Header.Name(NonEmptyString.unsafeFrom("X-Forwarded-User")).value.value should be("X-Forwarded-User")
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
      "reject a ror_metadata header which has another value than the real header" in {
        errorFrom(
          realHeaders = Map("X-Forwarded-User" -> "bob"),
          rorMetadataHeaders = "X-Forwarded-User:admin"
        ) should be(Header.AuthorizationValueError.HeaderValuesConflict(Header.Name.xForwardedUser, 1, 1))
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
            ) should be(Header.AuthorizationValueError.HeaderValuesConflict(Header.Name.xForwardedUser, 1, 1))
          }
        }
      }
    }
    "the same header arrives under two case spellings" should {
      // Netty keeps both spellings in `names()` and its `getAll` is case-insensitive, so Elasticsearch
      // hands ROR the same value list under each spelling.
      "not multiply the headers" in {
        val headers = Header.fromRawHeaders(
          Map(
            "X-Forwarded-User" -> List("bob"),
            "x-forwarded-user" -> List("bob")
          )
        ) match {
          case Right(result) => result
          case Left(error)   => fail(s"Cannot create headers: ${error.toString}")
        }

        valuesOf(headers, "x-forwarded-user") should be(List("bob"))
      }
    }
  }

  "Header.findHeader" should {
    "match the header name without case" in {
      val rawHeaders = new java.util.HashMap[String, java.util.List[String]]()
      rawHeaders.put("X-ROR-KBN-LICENSE-TYPE", java.util.List.of("enterprise"))

      Header
        .findHeader(Header.Name.rorKbnLicenseType, in = rawHeaders)
        .map(_.value.value) should be(Some("enterprise"))
    }
  }

  private def headersFrom(realHeaders: Map[String, String] = Map.empty, rorMetadataHeaders: String*) = {
    headersFromMultiValued(realHeaders.view.mapValues(List(_)).toMap, rorMetadataHeaders.toList)
  }

  private def headersFromMultiValued(
      realHeaders: Map[String, List[String]],
      rorMetadataHeaders: List[String]
  ) = {
    rawHeadersResultOf(realHeaders, rorMetadataHeaders) match {
      case Right(headers) => headers
      case Left(error)    => fail(s"Cannot create headers: ${error.toString}")
    }
  }

  private def errorFrom(realHeaders: Map[String, String], rorMetadataHeaders: String*) = {
    rawHeadersResultOf(realHeaders.view.mapValues(List(_)).toMap, rorMetadataHeaders.toList) match {
      case Left(error)    => error
      case Right(headers) => fail(s"Expected an error, but got headers: ${headers.toString}")
    }
  }

  private def rawHeadersResultOf(realHeaders: Map[String, List[String]], rorMetadataHeaders: List[String]) = {
    val rorMetadata = Base64.getEncoder.encodeToString(
      ujson
        .Obj("headers" -> ujson.Arr(rorMetadataHeaders.map(ujson.Str.apply)*))
        .render()
        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
    )
    val rawHeaders =
      realHeaders + ("Authorization" -> List(s"Basic dXNlcjpwYXNz, ror_metadata=$rorMetadata"))

    Header.fromRawHeaders(rawHeaders)
  }

  private def valuesOf(headers: Set[Header], name: String) =
    headers.toList
      .filter(_.name == Header.Name(NonEmptyString.unsafeFrom(name)))
      .map(_.value.value)

}

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
      "drop a ror_metadata header which clashes with a real header" in {
        val headers = headersFrom(
          realHeaders = Map("X-Forwarded-User" -> "bob"),
          rorMetadataHeaders = "X-Forwarded-User:admin"
        )

        valuesOf(headers, "x-forwarded-user") should be(List("bob"))
      }
      "drop a ror_metadata header which clashes with a real header written in a different case" in {
        val caseVariants = List(
          "x-forwarded-user",
          "X-FORWARDED-USER",
          "x-Forwarded-User",
          "X-forwarded-user",
          "X-Forwarded-user",
          "x-forwarded-User"
        )

        caseVariants.foreach { name =>
          val headers = headersFrom(
            realHeaders = Map("X-Forwarded-User" -> "bob"),
            rorMetadataHeaders = s"$name:admin"
          )

          withClue(s"ror_metadata header name: $name") {
            valuesOf(headers, "x-forwarded-user") should be(List("bob"))
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
    val rorMetadata = Base64.getEncoder.encodeToString(
      ujson
        .Obj("headers" -> ujson.Arr(rorMetadataHeaders.map(ujson.Str.apply)*))
        .render()
        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
    )
    val rawHeaders =
      realHeaders.view.mapValues(List(_)).toMap +
        ("Authorization" -> List(s"Basic dXNlcjpwYXNz, ror_metadata=$rorMetadata"))

    Header.fromRawHeaders(rawHeaders) match {
      case Right(headers) => headers
      case Left(error)    => fail(s"Cannot create headers: ${error.toString}")
    }
  }

  private def valuesOf(headers: Set[Header], name: String) =
    headers.toList
      .filter(_.name == Header.Name(NonEmptyString.unsafeFrom(name)))
      .map(_.value.value)

}

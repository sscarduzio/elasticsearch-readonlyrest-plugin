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
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.RorAuditDataStream
import tech.beshu.ror.accesscontrol.domain.RorAuditDataStream.CreationError

class RorAuditDataStreamTests extends AnyWordSpec with TableDrivenPropertyChecks {

  "A RorAuditDataStream" should {
    "be able to be created" in {
      forAll(
        Table(
          "Tested data stream name",
          "valid-stream",
          "stream123",
          "data-stream-99",
          "valid_stream",
          "a" * 255 // Exactly 255 bytes in length
        )
      ) { dataStream =>
        createRorAuditDataStream(dataStream) shouldBe a[Right[_, RorAuditDataStream]]
      }
    }
    "not be able to be created" in {
      forAll(
        Table(
          ("Tested data stream name", "Error cause"),
          ("InvalidUpper", lowercaseError),
          ("has space", forbiddenCharactersError),
          ("slash/name", forbiddenCharactersError),
          ("back\\slash", forbiddenCharactersError),
          ("star*name", forbiddenCharactersError),
          ("quote\"name", forbiddenCharactersError),
          ("less<greater>", forbiddenCharactersError),
          ("pipe|name", forbiddenCharactersError),
          ("comma,name", forbiddenCharactersError),
          ("hash#name", forbiddenCharactersError),
          ("colon:name", forbiddenCharactersError),
          ("-startsWithDash", lowercaseError + ", " + forbiddenPrefixError),
          ("_startsWithUnderscore", lowercaseError + ", " + forbiddenPrefixError),
          ("+startsWithPlus", lowercaseError + ", " + forbiddenPrefixError),
          (".ds-invalid", forbiddenPrefixError),
          (".", forbiddenNameError),
          ("..", forbiddenNameError),
          ("a" * 256, maxSizeExceededError), // Exceeds 255 bytes
          ("😊" * 85, maxSizeExceededError), // Exceeds 255 bytes due to multi-byte encoding
        )
      ) { case (dataStream: String, errorCause) =>
        createRorAuditDataStream(dataStream) shouldBe Left(error(dataStream, errorCause))
      }
    }
  }

  private def createRorAuditDataStream(str: String) = RorAuditDataStream(NonEmptyString.unsafeFrom(str))

  private def error(name: String, cause: String) =
    CreationError.FormatError(s"Data stream '$name' has an invalid format. Cause: $cause.")

  private lazy val lowercaseError = "name must be lowercase"
  private lazy val forbiddenCharactersError = "name must not contain forbidden characters '\\', '/', '*', '?', '\"', '<', '>', '|', ',', '#', ':', ' '"
  private lazy val forbiddenPrefixError = "name must not start with '-', '_', '+', '.ds-'"
  private lazy val forbiddenNameError = "name cannot be any of '.', '..'"
  private lazy val maxSizeExceededError = "name must be not longer than 255 bytes"
}

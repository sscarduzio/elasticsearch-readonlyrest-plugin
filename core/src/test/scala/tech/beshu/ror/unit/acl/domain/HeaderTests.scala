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

import org.scalatest.matchers.should.Matchers.*
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.Header
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*

import scala.jdk.CollectionConverters.*

class HeaderTests extends AnyWordSpec with TableDrivenPropertyChecks {

  private val currentGroupHeaderNames = Table(
    "Name which the client sends",
    "x-ror-current-group",
    "X-ROR-Current-Group",
    "X-Ror-Current-Group",
    "X-ROR-CURRENT-GROUP"
  )

  "Header.findHeader" should {
    "find the header in a set of headers" in {
      forAll(currentGroupHeaderNames) { name =>
        val headers: Set[Header] = Set(header(name, "group1"))

        val result = Header.findHeader(Header.Name.currentGroup, in = headers)

        result.map(_.value.value) should be(Some("group1"))
      }
    }
    "find the header in raw headers" in {
      forAll(currentGroupHeaderNames) { name =>
        val rawHeaders = Map(name -> List("group1").asJava).asJava

        val result = Header.findHeader(Header.Name.currentGroup, in = rawHeaders)

        result.map(_.value.value) should be(Some("group1"))
      }
    }
    "find no header" when {
      "the set has no header of the requested name" in {
        val headers: Set[Header] = Set(header("x-ror-correlation-id", "id1"))

        Header.findHeader(Header.Name.currentGroup, in = headers) should be(None)
      }
      "the raw headers have no header of the requested name" in {
        val rawHeaders = Map("x-ror-correlation-id" -> List("id1").asJava).asJava

        Header.findHeader(Header.Name.currentGroup, in = rawHeaders) should be(None)
      }
    }
  }

}

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
package tech.beshu.ror.unit.acl.domain.indices
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, RequestedIndex}
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.*
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.syntax.*

class IndicesFilteredByTests extends AnyWordSpec {

  "IndicesFilteredBy#filterBy" when {
    "both indices and requested indices are full names" should {
      "handle exact index matches" in {
        val indices = Set(
          clusterIndexName("test1"),
          clusterIndexName("test2"),
          clusterIndexName("other1")
        )
        
        val requestedIndices = List(
          RequestedIndex(clusterIndexName("test1"), excluded = false)
        )
        
        indices.filterBy(requestedIndices) shouldBe Set(
          RequestedIndex(clusterIndexName("test1"), excluded = false)
        )
      }
      "return empty set when no indices match" in {
        val indices = Set(
          clusterIndexName("test1"),
          clusterIndexName("test2")
        )
        
        val requestedIndices = List(
          RequestedIndex(clusterIndexName("nonexistent"), excluded = false)
        )
        
        indices.filterBy(requestedIndices) shouldBe Set.empty
      }
    }
    "indices are full names but requested indices have wildcards" should {
      "handle simple inclusion patterns" in {
        val indices = Set(
          clusterIndexName("test1"),
          clusterIndexName("test2"),
          clusterIndexName("other1")
        )
        
        val requestedIndices = List(
          RequestedIndex(clusterIndexName("test*"), excluded = false)
        )
        
        indices.filterBy(requestedIndices) shouldBe Set(
          RequestedIndex(clusterIndexName("test1"), excluded = false),
          RequestedIndex(clusterIndexName("test2"), excluded = false)
        )
      }
      "handle simple exclusion patterns" in {
        val indices = Set(
          clusterIndexName("test1"),
          clusterIndexName("test2"),
          clusterIndexName("other1")
        )
        
        val requestedIndices = List(
          RequestedIndex(clusterIndexName("test*"), excluded = true)
        )
        
        indices.filterBy(requestedIndices) shouldBe Set.empty
      }
      "handle multiple inclusion patterns" in {
        val indices = Set(
          clusterIndexName("test1"),
          clusterIndexName("test2"),
          clusterIndexName("prod1"),
          clusterIndexName("prod2"),
          clusterIndexName("other1")
        )
        
        val requestedIndices = List(
          RequestedIndex(clusterIndexName("test*"), excluded = false),
          RequestedIndex(clusterIndexName("prod*"), excluded = false)
        )
        
        indices.filterBy(requestedIndices) shouldBe Set(
          RequestedIndex(clusterIndexName("test1"), excluded = false),
          RequestedIndex(clusterIndexName("test2"), excluded = false),
          RequestedIndex(clusterIndexName("prod1"), excluded = false),
          RequestedIndex(clusterIndexName("prod2"), excluded = false)
        )
      }
    }
    "indices have wildcards but requested indices are full names" should {
      "match broader pattern in indices" in {
        val indices = List(
          clusterIndexName("te*"),
          clusterIndexName("other*")
        )
        
        val requestedIndices = List(
          RequestedIndex(clusterIndexName("test1"), excluded = false)
        )
        
        indices.filterBy(requestedIndices) shouldBe Set.empty
      }
    }
    "both indices and requested indices have wildcards" should {
      "handle matching wildcard patterns" in {
        val indices = List(
          clusterIndexName("test*"),
          clusterIndexName("prod-*"),
          clusterIndexName("other*")
        )
        
        val requestedIndices = List(
          RequestedIndex(clusterIndexName("test*"), excluded = false)
        )
        
        indices.filterBy(requestedIndices) shouldBe Set(
          RequestedIndex(clusterIndexName("test*"), excluded = false)
        )
      }
      "handle broader wildcard pattern in indices than in request" in {
        val indices = List(
          clusterIndexName("te*"),
          clusterIndexName("other*")
        )
        
        val requestedIndices = List(
          RequestedIndex(clusterIndexName("test*"), excluded = false)
        )
        
        indices.filterBy(requestedIndices) shouldBe Set.empty
      }
      "handle broader wildcard pattern in request than in indices" in {
        val indices = List(
          clusterIndexName("test*"),
          clusterIndexName("other*")
        )
        
        val requestedIndices = List(
          RequestedIndex(clusterIndexName("te*"), excluded = false)
        )
        
        indices.filterBy(requestedIndices) shouldBe Set(
          RequestedIndex(clusterIndexName("test*"), excluded = false)
        )
      }
      "handle mixed wildcard pattern breadth with exclusions" in {
        val indices = List(
          clusterIndexName("te*"),
          clusterIndexName("test*"),
          clusterIndexName("other*")
        )
        
        val requestedIndices = List(
          RequestedIndex(clusterIndexName("*"), excluded = false),
          RequestedIndex(clusterIndexName("test*"), excluded = true)
        )
        
        indices.filterBy(requestedIndices) shouldBe Set(
          RequestedIndex(clusterIndexName("te*"), excluded = false),
          RequestedIndex(clusterIndexName("other*"), excluded = false),
          RequestedIndex(clusterIndexName("test*"), excluded = true)
        )
      }
      "handle overlapping wildcards" in {
        val indices = List(
          clusterIndexName("test-*"),
          clusterIndexName("testing*")
        )
        
        val requestedIndices = List(
          RequestedIndex(clusterIndexName("test*"), excluded = false)
        )
        
        indices.filterBy(requestedIndices) shouldBe Set(
          RequestedIndex(clusterIndexName("test-*"), excluded = false),
          RequestedIndex(clusterIndexName("testing*"), excluded = false)
        )
      }
    }
  }
} 
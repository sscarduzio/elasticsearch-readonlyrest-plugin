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
package tech.beshu.ror.unit.acl.blocks.rules.indices

import cats.data.NonEmptySet
import eu.timepit.refined.auto.*
import monix.eval.Task
import tech.beshu.ror.accesscontrol.orders.requestedIndexOrder
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.domain.KibanaIndexName
import tech.beshu.ror.accesscontrol.orders.custerIndexNameOrder
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*

trait IndicesRuleLocalIndexTests {
  this: BaseIndicesRuleTests =>

  "An IndicesRule" should {
    "match" when {
      "no index passed, one is configured, there is one real index" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test")),
          requestIndices = Set.empty,
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(fullLocalIndexWithAliases(fullIndexName("test")))
          ),
          filteredRequestedIndices = Set(requestedIndex("test")),
        )
      }
      "'_all' passed, one is configured, there is one real index" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test")),
          requestIndices = Set(requestedIndex("_all")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(fullLocalIndexWithAliases(fullIndexName("test")))
          ),
          filteredRequestedIndices = Set(requestedIndex("test"))
        )
      }
      "'*' passed, one is configured, there is one real index" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test")),
          requestIndices = Set(requestedIndex("*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(fullLocalIndexWithAliases(fullIndexName("test")))
          ),
          filteredRequestedIndices = Set(requestedIndex("test"))
        )
      }
      "one full name index passed, one full name index configured, no real indices" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test")),
          requestIndices = Set(requestedIndex("test")),
          filteredRequestedIndices = Set(requestedIndex("test"))
        )
      }
      "one wildcard index passed, one full name index configured, no real indices" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test")),
          requestIndices = Set(requestedIndex("te*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(fullLocalIndexWithAliases(fullIndexName("test"))),
          ),
          filteredRequestedIndices = Set(requestedIndex("test"))
        )
      }
      "one full name index passed, one wildcard index configured, no real indices" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("t*")),
          requestIndices = Set(requestedIndex("test")),
          filteredRequestedIndices = Set(requestedIndex("test"))
        )
      }
      "two full name indexes passed, the same two full name indexes configured, no real indices" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1"), indexNameVar("test2")),
          requestIndices = Set(requestedIndex("test2"), requestedIndex("test1")),
          filteredRequestedIndices = Set(requestedIndex("test2"), requestedIndex("test1"))
        )
      }
      "two full name indexes passed, one the same, one different index configured, no real indices" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1"), indexNameVar("test2")),
          requestIndices = Set(requestedIndex("test1"), requestedIndex("test3")),
          filteredRequestedIndices = Set(requestedIndex("test1"))
        )
      }
      "two matching wildcard indexes passed, two full name indexes configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1"), indexNameVar("test2")),
          requestIndices = Set(requestedIndex("*2"), requestedIndex("*1")),
          filteredRequestedIndices = Set(requestedIndex("test1"), requestedIndex("test2"))
        )
      }
      "two full name indexes passed, two matching wildcard indexes configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*1"), indexNameVar("*2")),
          requestIndices = Set(requestedIndex("test2"), requestedIndex("test1")),
          filteredRequestedIndices = Set(requestedIndex("test2"), requestedIndex("test1"))
        )
      }
      "two full name indexes passed, one matching full name and one non-matching wildcard index configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1"), indexNameVar("*2")),
          requestIndices = Set(requestedIndex("test1"), requestedIndex("test3")),
          filteredRequestedIndices = Set(requestedIndex("test1"))
        )
      }
      "one matching wildcard index passed and one non-matching full name index, two full name indexes configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1"), indexNameVar("*2")),
          requestIndices = Set(requestedIndex("*1"), requestedIndex("test3")),
          filteredRequestedIndices = Set(requestedIndex("test1"))
        )
      }
      "one full name alias passed, full name index related to that alias configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test-index")),
          requestIndices = Set(requestedIndex("test-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              fullLocalIndexWithAliases(fullIndexName("test-index"), Set(fullIndexName("test-alias")))
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test-index"))
        )
      }
      "wildcard alias passed, full name alias configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test-alias")),
          requestIndices = Set(requestedIndex("test-al*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              fullLocalIndexWithAliases(fullIndexName("test-index"), Set(fullIndexName("test-alias")))
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test-alias"))
        )
      }
      "wildcard alias passed, full name index related to alias matching passed one configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test-index")),
          requestIndices = Set(requestedIndex("*-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              fullLocalIndexWithAliases(fullIndexName("test-index"), Set(fullIndexName("test-alias")))
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test-index"))
        )
      }
      "one full name alias passed, wildcard index configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*-index")),
          requestIndices = Set(requestedIndex("test-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              fullLocalIndexWithAliases(fullIndexName("test-index"), Set(fullIndexName("test-alias")))
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test-index"))
        )
      }
      "one alias passed, only subset of alias indices configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test-index1"), indexNameVar("test-index2")),
          requestIndices = Set(requestedIndex("test-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              fullLocalIndexWithAliases(fullIndexName("test-index1"), Set(fullIndexName("test-alias"))),
              fullLocalIndexWithAliases(fullIndexName("test-index2"), Set(fullIndexName("test-alias"))),
              fullLocalIndexWithAliases(fullIndexName("test-index3"), Set(fullIndexName("test-alias"))),
              fullLocalIndexWithAliases(fullIndexName("test-index4"), Set(fullIndexName("test-alias")))
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test-index1"), requestedIndex("test-index2"))
        )
      }
      "cross cluster index is used together with local index" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("odd:test1*"), indexNameVar("local*")),
          requestIndices = Set(requestedIndex("local_index*"), requestedIndex("odd:test1_index*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              fullLocalIndexWithAliases(fullIndexName("local_index1")),
              fullLocalIndexWithAliases(fullIndexName("local_index2")),
              fullLocalIndexWithAliases(fullIndexName("other"))
            ),
            allRemoteIndicesAndAliases = Task.now(Set(
              fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-27"),
              fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-28"),
              fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-29"),
              fullRemoteIndexWithAliases("odd", "test1_index1"),
              fullRemoteIndexWithAliases("odd", "test1_index2"),
              fullRemoteIndexWithAliases("odd", "test2_index1"),
            ))
          ),
          filteredRequestedIndices = Set(
            requestedIndex("local_index1"),
            requestedIndex("local_index2"),
            requestedIndex("odd:test1_index1"),
            requestedIndex("odd:test1_index2")
          )
        )
      }
      "multi filterable request tries to fetch data for allowed and not allowed index" in {
        assertMatchRuleForMultiIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1")),
          indexPacks = Indices.Found(Set(requestedIndex("test1"), requestedIndex("test2"))) :: Nil,
          allowed = Indices.Found(Set(requestedIndex("test1"))) :: Nil
        )
      }
      "kibana-related index in requested" when {
        "there is full name kibana index passed" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("odd:test1*"), indexNameVar("local*")),
            requestIndices = Set(requestedIndex(".custom_kibana_7.9.0")),
            modifyBlockContext = bc => bc.copy(
              userMetadata = bc.userMetadata.withKibanaIndex(KibanaIndexName(localIndexName(".custom_kibana")))
            ),
            filteredRequestedIndices = Set(requestedIndex(".custom_kibana_7.9.0"))
          )
        }
        "there are full name kibana indices passed" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("odd:test1*"), indexNameVar("local*")),
            requestIndices = Set(requestedIndex(".custom_kibana_8.10.4"), requestedIndex(".custom_kibana_task_manager_8.10.4")),
            modifyBlockContext = bc => bc.copy(
              userMetadata = bc.userMetadata.withKibanaIndex(KibanaIndexName(localIndexName(".custom_kibana")))
            ),
            filteredRequestedIndices = Set(requestedIndex(".custom_kibana_8.10.4"), requestedIndex(".custom_kibana_task_manager_8.10.4"))
          )
        }
      }
      "some indices are excluded" when {
        "todo" in { // todo
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("test-index1-*")),
            requestIndices = Set(requestedIndex("test-index*"), requestedIndex("-*old")),
            modifyRequestContext = _.copy(
              allIndicesAndAliases = Set(
                fullLocalIndexWithAliases(fullIndexName("test-index1-0001")),
                fullLocalIndexWithAliases(fullIndexName("test-index1-0002")),
                fullLocalIndexWithAliases(fullIndexName("test-index1-old")),
                fullLocalIndexWithAliases(fullIndexName("test-index2-0001")),
                fullLocalIndexWithAliases(fullIndexName("test-index2-old")),
              )
            ),
            filteredRequestedIndices = Set(
              requestedIndex("test-index1-0001"),
              requestedIndex("test-index1-0002")
            ),
          )
        }
      }
    }
    "not match" when {
      "no index passed, one is configured, no real indices" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test")),
          requestIndices = Set.empty,
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty
          )
        )
      }
      "'_all' passed, one is configured, no real indices" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test")),
          requestIndices = Set(requestedIndex("_all"))
        )
      }
      "'*' passed, one is configured, no real indices" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test")),
          requestIndices = Set(requestedIndex("*"))
        )
      }
      "one full name index passed, different one full name index configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1")),
          requestIndices = Set(requestedIndex("test2"))
        )
      }
      "one wildcard index passed, non-matching index with full name configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1")),
          requestIndices = Set(requestedIndex("*2"))
        )
      }
      "one full name index passed, non-matching index with wildcard configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*1")),
          requestIndices = Set(requestedIndex("test2"))
        )
      }
      "two full name indexes passed, different two full name indexes configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1"), indexNameVar("test2")),
          requestIndices = Set(requestedIndex("test4"), requestedIndex("test3"))
        )
      }
      "two wildcard indexes passed, non-matching two full name indexes configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1"), indexNameVar("test2")),
          requestIndices = Set(requestedIndex("*4"), requestedIndex("*3"))
        )
      }
      "two full name indexes passed, non-matching two wildcard indexes configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*1"), indexNameVar("*2")),
          requestIndices = Set(requestedIndex("test4"), requestedIndex("test3"))
        )
      }
      "one full name alias passed, full name index with no alias configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test-index")),
          requestIndices = Set(requestedIndex("test-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              fullLocalIndexWithAliases(fullIndexName("test-index")),
              fullLocalIndexWithAliases(fullIndexName("test-index2"), Set(fullIndexName("test-alias")))
            )
          )
        )
      }
      "wildcard alias passed, full name index with no alias configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test-index")),
          requestIndices = Set(requestedIndex("*-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              fullLocalIndexWithAliases(fullIndexName("test-index"), Set.empty),
              fullLocalIndexWithAliases(fullIndexName("test-index2"), Set(fullIndexName("test-alias")))
            )
          )
        )
      }
      "full name index passed, index alias configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test12-alias")),
          requestIndices = Set(requestedIndex("test-index1")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              fullLocalIndexWithAliases(fullIndexName("test-index1"), Set(fullIndexName("test12-alias"))),
              fullLocalIndexWithAliases(fullIndexName("test-index2"), Set(fullIndexName("test12-alias"))),
              fullLocalIndexWithAliases(fullIndexName("test-index3"), Set(fullIndexName("test34-alias"))),
              fullLocalIndexWithAliases(fullIndexName("test-index4"), Set(fullIndexName("test34-alias")))
            )
          )
        )
      }
      "there is only one kibana-related index" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test12")),
          requestIndices = Set(requestedIndex(".kibana_8.10.4"), requestedIndex("test-index1")),
          modifyBlockContext = bc => bc.copy(
            userMetadata = bc.userMetadata.withKibanaIndex(KibanaIndexName(localIndexName(".kibana")))
          ),
        )
      }
    }
  }
}

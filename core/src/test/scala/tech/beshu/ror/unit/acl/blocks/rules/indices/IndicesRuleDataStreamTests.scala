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
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.orders.indexOrder
import tech.beshu.ror.utils.TestsUtils.*

trait IndicesRuleDataStreamTests {
  this: BaseIndicesRuleTests =>

  "An IndicesRule for data streams" should {
    "match" when {
      "no data stream passed, one is configured, there is one real data stream" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test_ds")),
          requestIndices = Set.empty,
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test_ds")))
          ),
          filteredRequestedIndices = Set(requestedIndex("test_ds")),
        )
      }
      "no data stream passed, one is configured, there is two real data streams" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds")),
          requestIndices = Set.empty,
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds")),
              fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"))
            ),
          ),
          filteredRequestedIndices = Set(requestedIndex("test1_ds")),
        )
      }
      "'_all' passed, one is configured, there is two real data streams" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds")),
          requestIndices = Set(requestedIndex("_all")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds")),
              fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"))
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test1_ds"))
        )
      }
      "'*' passed, one is configured, there is one real data stream" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds")),
          requestIndices = Set(requestedIndex("*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds")),
              fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"))
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test1_ds"))
        )
      }
      "one full name data stream passed, one full name data stream configured, no real data streams" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test_ds")),
          requestIndices = Set(requestedIndex("test_ds")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set.empty
          ),
          filteredRequestedIndices = Set(requestedIndex("test_ds"))
        )
      }
      "one wildcard data stream passed, one full name data stream configured, no real data streams" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test_ds")),
          requestIndices = Set(requestedIndex("te*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test_ds"))),
          ),
          filteredRequestedIndices = Set(requestedIndex("test_ds"))
        )
      }
      "one full name data stream passed, one wildcard data stream configured, no real data streams" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("t*")),
          requestIndices = Set(requestedIndex("test_ds")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          ),
          filteredRequestedIndices = Set(requestedIndex("test_ds"))
        )
      }
      "two full name data streams passed, the same two full name data streams configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds"), indexNameVar("test2_ds")),
          requestIndices = Set(requestedIndex("test2_ds"), requestedIndex("test1_ds")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          ),
          filteredRequestedIndices = Set(requestedIndex("test2_ds"), requestedIndex("test1_ds"))
        )
      }
      "two full name dat streams passed, one the same, one different data stream configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds"), indexNameVar("test2_ds")),
          requestIndices = Set(requestedIndex("test1_ds"), requestedIndex("test3_ds")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          ),
          filteredRequestedIndices = Set(requestedIndex("test1_ds"))
        )
      }
      "two matching wildcard data streams passed, two full name data streams configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds"), indexNameVar("test2_ds")),
          requestIndices = Set(requestedIndex("*2_ds"), requestedIndex("*1_ds")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          ),
          filteredRequestedIndices = Set(requestedIndex("test1_ds"), requestedIndex("test2_ds"))
        )
      }
      "two full name data streams passed, two matching wildcard data streams configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*1_ds"), indexNameVar("*2_ds")),
          requestIndices = Set(requestedIndex("test2_ds"), requestedIndex("test1_ds")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          ),
          filteredRequestedIndices = Set(requestedIndex("test2_ds"), requestedIndex("test1_ds"))
        )
      }
      "two full name data streams passed, one matching full name and one non-matching wildcard data stream configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds"), indexNameVar("*2")),
          requestIndices = Set(requestedIndex("test1_ds"), requestedIndex("test3_ds")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          ),
          filteredRequestedIndices = Set(requestedIndex("test1_ds"))
        )
      }
      "one matching wildcard data stream passed and one non-matching full name index, two full name data streams configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds"), indexNameVar("*2_ds")),
          requestIndices = Set(requestedIndex("*1_ds"), requestedIndex("test3_ds")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          ),
          filteredRequestedIndices = Set(requestedIndex("test1_ds"))
        )
      }
      "one full name alias passed, full name data stream related to that alias configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds")),
          requestIndices = Set(requestedIndex("test_alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"), Set(fullDataStreamName("test_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"), Set(fullDataStreamName("test_alias")))
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test1_ds"))
        )
      }
      "wildcard alias passed, full name data stream related to alias matching passed one configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds")),
          requestIndices = Set(requestedIndex("*_alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"), Set(fullDataStreamName("test_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"), Set(fullDataStreamName("test_alias")))
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test1_ds"))
        )
      }
      "one full name alias passed, wildcard data stream configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*_ds")),
          requestIndices = Set(requestedIndex("test_alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"), Set(fullDataStreamName("test_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"), Set(fullDataStreamName("test_alias"))),
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test1_ds"), requestedIndex("test2_ds"))
        )
      }
      "one alias passed, only subset of alias data streams configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds"), indexNameVar("test2_ds")),
          requestIndices = Set(requestedIndex("test_alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"), Set(fullDataStreamName("test_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"), Set(fullDataStreamName("test_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test3_ds"), Set(fullDataStreamName("test_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test4_ds"), Set(fullDataStreamName("test_alias")))
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test1_ds"), requestedIndex("test2_ds"))
        )
      }
      "one alias passed, one alias configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test_alias")),
          requestIndices = Set(requestedIndex("test_alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"), Set(fullDataStreamName("test_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"), Set(fullDataStreamName("test_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test3_ds"), Set(fullDataStreamName("test_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test4_ds"), Set(fullDataStreamName("test_alias")))
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test_alias"))
        )
      }
      "one alias pattern passed, one alias configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test_alias")),
          requestIndices = Set(requestedIndex("test_al*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"), Set(fullDataStreamName("test_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"), Set(fullDataStreamName("test_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test3_ds"), Set(fullDataStreamName("test_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test4_ds"), Set(fullDataStreamName("test_alias")))
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test_alias"))
        )
      }
      "one backing index passed, one data stream configured, there is one real data stream" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds")),
          requestIndices = Set(requestedIndex(".ds-test1_ds")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"))),
          ),
          filteredRequestedIndices = Set(
            requestedIndex(".ds-test1_ds"),
          )
        )
      }
      "one backing index pattern passed, one data stream configured, there is one real data stream" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds")),
          requestIndices = Set(requestedIndex(".ds-test*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"))),
          ),
          filteredRequestedIndices = Set(
            requestedIndex(".ds-test1_ds"),
          )
        )
      }
      "one backing index pattern passed, one backing index configured, there is one real data stream" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar(".ds-test1_ds")),
          requestIndices = Set(requestedIndex(".ds-test*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"))),
          ),
          filteredRequestedIndices = Set(
            requestedIndex(".ds-test1_ds"),
          )
        )
      }
      "one backing index pattern passed, one backing index pattern configured, there is one real data stream" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar(".ds-test1*")),
          requestIndices = Set(requestedIndex(".ds-test*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"))),
          ),
          filteredRequestedIndices = Set(
            requestedIndex(".ds-test1_ds"),
          )
        )
      }
      "one backing index passed, data stream pattern configured, there is one real data stream" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test*")),
          requestIndices = Set(requestedIndex(".ds-test1_ds")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"))),
          ),
          filteredRequestedIndices = Set(
            requestedIndex(".ds-test1_ds"),
          )
        )
      }
      "one backing index pattern passed, data stream pattern configured, there is one real data stream" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test*")),
          requestIndices = Set(requestedIndex(".ds-test*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"))),
          ),
          filteredRequestedIndices = Set(
            requestedIndex(".ds-test1_ds"),
          )
        )
      }
      "cross cluster data stream is used together with local data stream" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("es_pl:test1*"), indexNameVar("local*")),
          requestIndices = Set(requestedIndex("local_ds*"), requestedIndex("es_pl:test1_ds*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("local_ds1")),
              fullLocalDataStreamWithAliases(fullDataStreamName("local_ds2")),
              fullLocalDataStreamWithAliases(fullDataStreamName("other"))
            ),
            allRemoteDataStreamsAndAliases = Task.now(Set(
              fullRemoteDataStreamWithAliases("es_us", "test1_ds1"),
              fullRemoteDataStreamWithAliases("es_us", "test1_ds2"),
              fullRemoteDataStreamWithAliases("es_us", "test2_ds1"),
              fullRemoteDataStreamWithAliases("es_pl", "test1_ds1"),
              fullRemoteDataStreamWithAliases("es_pl", "test1_ds2"),
              fullRemoteDataStreamWithAliases("es_pl", "test2_ds1"),
            ))
          ),
          filteredRequestedIndices = Set(
            requestedIndex("local_ds1"),
            requestedIndex("local_ds2"),
            requestedIndex("es_pl:test1_ds1"),
            requestedIndex("es_pl:test1_ds2")
          )
        )
      }
      "cross cluster data stream backing index is used when data stream configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*:test1_ds")),
          requestIndices = Set(requestedIndex("es_us:.ds-test1_ds")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set.empty,
            allRemoteDataStreamsAndAliases = Task.now(Set(
              fullRemoteDataStreamWithAliases("es_us", "test1_ds"),
            ))
          ),
          filteredRequestedIndices = Set(
            requestedIndex("es_us:.ds-test1_ds"),
          )
        )
      }
      "multi filterable request tries to fetch data for allowed and not allowed data streams" in {
        assertMatchRuleForMultiIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds")),
          indexPacks = Indices.Found(Set(requestedIndex("test1_ds"), requestedIndex("test2_ds"))) :: Nil,
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          ),
          allowed = Indices.Found(Set(requestedIndex("test1_ds"))) :: Nil
        )
      }
    }
    "not match" when {
      "no data stream passed, one is configured, no real data streams" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test_ds")),
          requestIndices = Set.empty,
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set.empty
          )
        )
      }
      "'_all' passed, one is configured, no real data streams" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test_ds")),
          requestIndices = Set(requestedIndex("_all")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set.empty
          )
        )
      }
      "'*' passed, one is configured, no real data streams" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test_ds")),
          requestIndices = Set(requestedIndex("*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set.empty
          )
        )
      }
      "one full name data stream passed, different one full name data stream configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds")),
          requestIndices = Set(requestedIndex("test2_ds")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          )
        )
      }
      "one wildcard data stream passed, non-matching data stream with full name configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds")),
          requestIndices = Set(requestedIndex("*2")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          )
        )
      }
      "one full name data stream passed, non-matching data stream with wildcard configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*1")),
          requestIndices = Set(requestedIndex("test2_ds")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          )
        )
      }
      "two full name data streams passed, different two full name data streams configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds"), indexNameVar("test2_ds")),
          requestIndices = Set(requestedIndex("test4_ds"), requestedIndex("test3_ds")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          )
        )
      }
      "two wildcard data streams passed, non-matching two full name data streams configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds"), indexNameVar("test2_ds")),
          requestIndices = Set(requestedIndex("*4"), requestedIndex("*3")),
          modifyRequestContext = _.copy(
            allDataStreamsAndAliases = testDataStreams
          )
        )
      }
      "two full name data streams passed, non-matching two wildcard data streams configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*1"), indexNameVar("*2")),
          requestIndices = Set(requestedIndex("test4_ds"), requestedIndex("test3_ds")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          )
        )
      }
      "one full name alias passed, full name data stream with no alias configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds")),
          requestIndices = Set(requestedIndex("test_alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds")),
              fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"), Set(fullDataStreamName("test_alias")))
            )
          )
        )
      }
      "one data stream name passed, full name data stream alias configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test_alias")),
          requestIndices = Set(requestedIndex("test2_ds")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds")),
              fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"), Set(fullDataStreamName("test_alias")))
            )
          )
        )
      }
      "wildcard alias passed, full name data stream with no alias configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds")),
          requestIndices = Set(requestedIndex("*_alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"), Set.empty),
              fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"), Set(fullDataStreamName("test_alias")))
            )
          )
        )
      }
      "full name data stream passed, data stream alias configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test12_alias")),
          requestIndices = Set(requestedIndex("test1_ds")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"), Set(fullDataStreamName("test12_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"), Set(fullDataStreamName("test12_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test3_ds"), Set(fullDataStreamName("test34_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test4_ds"), Set(fullDataStreamName("test34_alias")))
            )
          )
        )
      }
      "one backing index passed, one full data stream alias configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test_alias1")),
          requestIndices = Set(requestedIndex(".ds-test1_ds")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"), Set(fullDataStreamName("test1_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"), Set(fullDataStreamName("test1_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test3_ds"), Set(fullDataStreamName("test2_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test4_ds"), Set(fullDataStreamName("test2_alias")))
            )
          )
        )
      }
      "one backing index passed, one data stream alias pattern configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_al*")),
          requestIndices = Set(requestedIndex(".ds-test1_ds")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"), Set(fullDataStreamName("test1_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"), Set(fullDataStreamName("test1_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test3_ds"), Set(fullDataStreamName("test2_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test4_ds"), Set(fullDataStreamName("test2_alias")))
            )
          )
        )
      }
      "one backing index pattern passed, one full data stream alias configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_alias")),
          requestIndices = Set(requestedIndex(".ds-test*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"), Set(fullDataStreamName("test1_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"), Set(fullDataStreamName("test1_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test3_ds"), Set(fullDataStreamName("test2_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test4_ds"), Set(fullDataStreamName("test2_alias")))
            )
          )
        )
      }
      "one backing index pattern passed, one data stream alias pattern configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test_al*")),
          requestIndices = Set(requestedIndex(".ds-test*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"), Set(fullDataStreamName("test1_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"), Set(fullDataStreamName("test1_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test3_ds"), Set(fullDataStreamName("test2_alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test4_ds"), Set(fullDataStreamName("test2_alias")))
            )
          )
        )
      }
    }
  }

  "An IndicesRule for request with remote data streams" should {
    "match" when {
      "remote data streams are used" when {
        "requested data stream name with wildcard is the same as configured data stream name with wildcard" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("es_u*:*-logs-*")),
            requestIndices = Set(requestedIndex("e*:*-logs-*")),
            modifyRequestContext = _.copy(
              allIndicesAndAliases = Set.empty,
              allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test_ds"), Set.empty)),
              allRemoteDataStreamsAndAliases = Task.now(Set(
                fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-27"),
                fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-28"),
                fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-29"),
                fullRemoteDataStreamWithAliases("es_uk", "other-index"),
                fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-27"),
                fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-28"),
                fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-29")
              ))
            ),
            filteredRequestedIndices = Set(
              requestedIndex("es_us:c01-logs-2020-03-27"),
              requestedIndex("es_us:c01-logs-2020-03-28"),
              requestedIndex("es_us:c01-logs-2020-03-29")
            )
          )
        }
        "requested data stream name with wildcard is more general version of the configured data stream name with wildcard" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("es_u*:*-logs-*")),
            requestIndices = Set(requestedIndex("e*:*-logs-*")),
            modifyRequestContext = _.copy(
              allIndicesAndAliases = Set.empty,
              allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test_ds"), Set.empty)),
              allRemoteDataStreamsAndAliases = Task.now(Set(
                fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-27"),
                fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-28"),
                fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-29"),
                fullRemoteDataStreamWithAliases("es_uk", "other-index"),
                fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-27"),
                fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-28"),
                fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-29")
              ))
            ),
            filteredRequestedIndices = Set(
              requestedIndex("es_us:c01-logs-2020-03-27"),
              requestedIndex("es_us:c01-logs-2020-03-28"),
              requestedIndex("es_us:c01-logs-2020-03-29")
            )
          )
        }
        "requested data stream name with wildcard is more specialized version of the configured data stream name with wildcard" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("es_u*:*-logs-*")),
            requestIndices = Set(requestedIndex("e*:*-logs-2020-03-2*")),
            modifyRequestContext = _.copy(
              allIndicesAndAliases = Set.empty,
              allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test_ds"), Set.empty)),
              allRemoteDataStreamsAndAliases = Task.now(Set(
                fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-27"),
                fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-28"),
                fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-29"),
                fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-30"),
                fullRemoteDataStreamWithAliases("es_uk", "other-index"),
                fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-27"),
                fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-28"),
                fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-29")
              ))
            ),
            filteredRequestedIndices = Set(
              requestedIndex("es_us:c01-logs-2020-03-27"),
              requestedIndex("es_us:c01-logs-2020-03-28"),
              requestedIndex("es_us:c01-logs-2020-03-29")
            )
          )
        }
        "requested data stream name with wildcard doesn't match the configured data stream name with wildcard but it does match the resolved data stream name" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("es_u*:*-logs-*")),
            requestIndices = Set(requestedIndex("e*:c0*")),
            modifyRequestContext = _.copy(
              allIndicesAndAliases = Set.empty,
              allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test_ds"), Set.empty)),
              allRemoteDataStreamsAndAliases = Task.now(Set(
                fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-27"),
                fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-28"),
                fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-29"),
                fullRemoteDataStreamWithAliases("es_uk", "other-index"),
                fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-27"),
                fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-28"),
                fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-29")
              ))
            ),
            filteredRequestedIndices = Set(
              requestedIndex("es_us:c01-logs-2020-03-27"),
              requestedIndex("es_us:c01-logs-2020-03-28"),
              requestedIndex("es_us:c01-logs-2020-03-29")
            )
          )
        }
        "requested data stream alias pattern when data stream configured" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("*:test1_ds")),
            requestIndices = Set(requestedIndex("es_us:test1_al*")),
            modifyRequestContext = _.copy(
              allIndicesAndAliases = Set.empty,
              allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test_ds"), Set.empty)),
              allRemoteDataStreamsAndAliases = Task.now(Set(
                fullRemoteDataStreamWithAliases("es_us", "test1_ds", "test1_alias"),
                fullRemoteDataStreamWithAliases("es_us", "test2_ds", "test10_alias"),
                fullRemoteDataStreamWithAliases("es_us", "test3_ds", "test2_alias"),
              ))
            ),
            filteredRequestedIndices = Set(
              requestedIndex("es_us:test1_ds")
            )
          )
        }
        "requested data stream alias when data stream configured" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("*:test_ds")),
            requestIndices = Set(requestedIndex("es_us:test_alias")),
            modifyRequestContext = _.copy(
              allIndicesAndAliases = Set.empty,
              allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test_ds"), Set.empty)),
              allRemoteDataStreamsAndAliases = Task.now(Set(
                fullRemoteDataStreamWithAliases("es_us", "test_ds", "test_alias"),
                fullRemoteDataStreamWithAliases("es_us", "test1_ds", "test_alias"),
                fullRemoteDataStreamWithAliases("es_us", "test2_ds", "test_alias"),
              ))
            ),
            filteredRequestedIndices = Set(
              requestedIndex("es_us:test_ds")
            )
          )
        }
        "requested data stream alias when data stream alias configured" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("*:test_alias")),
            requestIndices = Set(requestedIndex("es_us:test_alias")),
            modifyRequestContext = _.copy(
              allIndicesAndAliases = Set.empty,
              allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test_ds"), Set.empty)),
              allRemoteDataStreamsAndAliases = Task.now(Set(
                fullRemoteDataStreamWithAliases("es_us", "test_ds", "test_alias"),
                fullRemoteDataStreamWithAliases("es_us", "test1_ds", "test_alias"),
                fullRemoteDataStreamWithAliases("es_us", "test2_ds", "test_alias"),
              ))
            ),
            filteredRequestedIndices = Set(
              requestedIndex("es_us:test_alias")
            )
          )
        }
        "requested data stream alias pattern when data stream alias configured" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("*:test_alias")),
            requestIndices = Set(requestedIndex("es_us:test_al*")),
            modifyRequestContext = _.copy(
              allIndicesAndAliases = Set.empty,
              allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test_ds"), Set.empty)),
              allRemoteDataStreamsAndAliases = Task.now(Set(
                fullRemoteDataStreamWithAliases("es_us", "test1_ds", "test_alias"),
                fullRemoteDataStreamWithAliases("es_us", "test2_ds", "test_alias"),
              ))
            ),
            filteredRequestedIndices = Set(
              requestedIndex("es_us:test_alias")
            )
          )
        }
      }
    }
    "not match" when {
      "not allowed cluster data streams are being called" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*-logs-*"), indexNameVar("es_u*:*-logs-*")),
          requestIndices = Set(requestedIndex("pub*:*logs*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("clocal-logs-2020-03-27")),
              fullLocalDataStreamWithAliases(fullDataStreamName("clocal-logs-2020-03-28")),
              fullLocalDataStreamWithAliases(fullDataStreamName("clocal-logs-2020-03-29")),
            ),
            allRemoteDataStreamsAndAliases = Task.now(Set(
              fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-27"),
              fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-28"),
              fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-29"),
              fullRemoteDataStreamWithAliases("es_us", "other-index"),
              fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-27"),
              fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-28"),
              fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-29")
            ))
          )
        )
      }
      "cross cluster data stream backing index is used when data stream alias configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*:test_alias")),
          requestIndices = Set(requestedIndex("es_us:.ds-test1_ds")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set.empty,
            allRemoteDataStreamsAndAliases = Task.now(Set(
              fullRemoteDataStreamWithAliases("es_us", "test1_ds", "test_alias"),
            ))
          )
        )
      }
    }
  }

  private def testDataStreams = Set(
    fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds")),
    fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds")),
    fullLocalDataStreamWithAliases(fullDataStreamName("test3_ds")),
    fullLocalDataStreamWithAliases(fullDataStreamName("test4_ds")),
    fullLocalDataStreamWithAliases(fullDataStreamName("test5_ds"))
  )
}

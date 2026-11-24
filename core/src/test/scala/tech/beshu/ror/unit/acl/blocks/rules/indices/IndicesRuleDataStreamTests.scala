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
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.Decision.Denied.Cause.NotAuthorized
import tech.beshu.ror.accesscontrol.blocks.Decision.{Denied, Permitted}
import tech.beshu.ror.accesscontrol.domain.{Action, DataStreamName}
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.accesscontrol.orders.custerIndexNameOrder
import tech.beshu.ror.mocks.MockEsServices
import tech.beshu.ror.mocks.MockEsServices.MockEsClusterService
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*

trait IndicesRuleDataStreamTests {
  this: BaseIndicesRuleTests =>

  "An IndicesRule for data streams" should {
    "match" when {
      "no data stream passed, one is configured, there is one real data stream" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test_ds")),
          requestIndices = Set.empty,
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test_ds")))
              )
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test_ds")),
        )
      }
      "no data stream passed, one is configured, there is two real data streams" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds")),
          requestIndices = Set.empty,
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(
                  fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds")),
                  fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"))
                ),
              )
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test1_ds")),
        )
      }
      "'_all' passed, one is configured, there is two real data streams" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds")),
          requestIndices = Set(requestedIndex("_all")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(
                  fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds")),
                  fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"))
                )
              )
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test1_ds"))
        )
      }
      "'*' passed, one is configured, there is one real data stream" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds")),
          requestIndices = Set(requestedIndex("*")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(
                  fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds")),
                  fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"))
                )
              )
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test1_ds"))
        )
      }
      "one full name data stream passed, one full name data stream configured, no real data streams" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test_ds")),
          requestIndices = Set(requestedIndex("test_ds")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set.empty
              )
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test_ds"))
        )
      }
      "one wildcard data stream passed, one full name data stream configured, no real data streams" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test_ds")),
          requestIndices = Set(requestedIndex("te*")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test_ds"))),
              )
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test_ds"))
        )
      }
      "one full name data stream passed, one wildcard data stream configured, no real data streams" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("t*")),
          requestIndices = Set(requestedIndex("test_ds")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = testDataStreams
              )
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test_ds"))
        )
      }
      "two full name data streams passed, the same two full name data streams configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds"), indexNameVar("test2_ds")),
          requestIndices = Set(requestedIndex("test2_ds"), requestedIndex("test1_ds")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = testDataStreams
              )
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test2_ds"), requestedIndex("test1_ds"))
        )
      }
      "two full name dat streams passed, one the same, one different data stream configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds"), indexNameVar("test2_ds")),
          requestIndices = Set(requestedIndex("test1_ds"), requestedIndex("test3_ds")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = testDataStreams
              )
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test1_ds"))
        )
      }
      "two matching wildcard data streams passed, two full name data streams configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds"), indexNameVar("test2_ds")),
          requestIndices = Set(requestedIndex("*2_ds"), requestedIndex("*1_ds")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = testDataStreams
              )
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test1_ds"), requestedIndex("test2_ds"))
        )
      }
      "two full name data streams passed, two matching wildcard data streams configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*1_ds"), indexNameVar("*2_ds")),
          requestIndices = Set(requestedIndex("test2_ds"), requestedIndex("test1_ds")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = testDataStreams
              )
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test2_ds"), requestedIndex("test1_ds"))
        )
      }
      "two full name data streams passed, one matching full name and one non-matching wildcard data stream configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds"), indexNameVar("*2")),
          requestIndices = Set(requestedIndex("test1_ds"), requestedIndex("test3_ds")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = testDataStreams
              )
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test1_ds"))
        )
      }
      "one matching wildcard data stream passed and one non-matching full name index, two full name data streams configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds"), indexNameVar("*2_ds")),
          requestIndices = Set(requestedIndex("*1_ds"), requestedIndex("test3_ds")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = testDataStreams
              )
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test1_ds"))
        )
      }
      "one full name alias passed, full name data stream related to that alias configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds")),
          requestIndices = Set(requestedIndex("test_alias")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(
                  fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"), Set(fullDataStreamName("test_alias"))),
                  fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"), Set(fullDataStreamName("test_alias")))
                )
              )
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test1_ds"))
        )
      }
      "wildcard alias passed, full name data stream related to alias matching passed one configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds")),
          requestIndices = Set(requestedIndex("*_alias")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(
                  fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"), Set(fullDataStreamName("test_alias"))),
                  fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"), Set(fullDataStreamName("test_alias")))
                )
              )
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test1_ds"))
        )
      }
      "one full name alias passed, wildcard data stream configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*_ds")),
          requestIndices = Set(requestedIndex("test_alias")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(
                  fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"), Set(fullDataStreamName("test_alias"))),
                  fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"), Set(fullDataStreamName("test_alias"))),
                )
              )
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test1_ds"), requestedIndex("test2_ds"))
        )
      }
      "one alias passed, only subset of alias data streams configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds"), indexNameVar("test2_ds")),
          requestIndices = Set(requestedIndex("test_alias")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(
                  fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"), Set(fullDataStreamName("test_alias"))),
                  fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"), Set(fullDataStreamName("test_alias"))),
                  fullLocalDataStreamWithAliases(fullDataStreamName("test3_ds"), Set(fullDataStreamName("test_alias"))),
                  fullLocalDataStreamWithAliases(fullDataStreamName("test4_ds"), Set(fullDataStreamName("test_alias")))
                )
              )
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test1_ds"), requestedIndex("test2_ds"))
        )
      }
      "one alias passed, one alias configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test_alias")),
          requestIndices = Set(requestedIndex("test_alias")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(
                  fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"), Set(fullDataStreamName("test_alias"))),
                  fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"), Set(fullDataStreamName("test_alias"))),
                  fullLocalDataStreamWithAliases(fullDataStreamName("test3_ds"), Set(fullDataStreamName("test_alias"))),
                  fullLocalDataStreamWithAliases(fullDataStreamName("test4_ds"), Set(fullDataStreamName("test_alias")))
                )
              )
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test_alias"))
        )
      }
      "one alias pattern passed, one alias configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test_alias")),
          requestIndices = Set(requestedIndex("test_al*")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(
                  fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"), Set(fullDataStreamName("test_alias"))),
                  fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"), Set(fullDataStreamName("test_alias"))),
                  fullLocalDataStreamWithAliases(fullDataStreamName("test3_ds"), Set(fullDataStreamName("test_alias"))),
                  fullLocalDataStreamWithAliases(fullDataStreamName("test4_ds"), Set(fullDataStreamName("test_alias")))
                )
              )
            )
          ),
          filteredRequestedIndices = Set(requestedIndex("test_alias"))
        )
      }
      "one backing index passed, one data stream configured, there is one real data stream" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds")),
          requestIndices = Set(requestedIndex(".ds-test1_ds")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"))),
              )
            )
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
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"))),
              )
            )
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
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"))),
              )
            )
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
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"))),
              )
            )
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
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"))),
              )
            )
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
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"))),
              )
            )
          ),
          filteredRequestedIndices = Set(
            requestedIndex(".ds-test1_ds"),
          )
        )
      }
      "regular index and backing index passed, both allowed by rule" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("my-index"), indexNameVar("test1_ds")),
          requestIndices = Set(requestedIndex("my-index"), requestedIndex(".ds-test1_ds")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set(fullLocalIndexWithAliases(fullIndexName("my-index"))),
                allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"))),
              )
            )
          ),
          filteredRequestedIndices = Set(
            requestedIndex("my-index"),
            requestedIndex(".ds-test1_ds"),
          )
        )
      }
      "regular index and backing index passed, only regular index allowed by rule" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("my-index")),
          requestIndices = Set(requestedIndex("my-index"), requestedIndex(".ds-test1_ds")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set(fullLocalIndexWithAliases(fullIndexName("my-index"))),
                allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"))),
              )
            )
          ),
          filteredRequestedIndices = Set(
            requestedIndex("my-index"),
          )
        )
      }
      "regular index and backing index passed, only backing index allowed by rule" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds")),
          requestIndices = Set(requestedIndex("my-index"), requestedIndex(".ds-test1_ds")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set(fullLocalIndexWithAliases(fullIndexName("my-index"))),
                allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"))),
              )
            )
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
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(
                  fullLocalDataStreamWithAliases(fullDataStreamName("local_ds1")),
                  fullLocalDataStreamWithAliases(fullDataStreamName("local_ds2")),
                  fullLocalDataStreamWithAliases(fullDataStreamName("other"))
                ),
                allRemoteDataStreamsAndAliases = Set(
                  fullRemoteDataStreamWithAliases("es_us", "test1_ds1"),
                  fullRemoteDataStreamWithAliases("es_us", "test1_ds2"),
                  fullRemoteDataStreamWithAliases("es_us", "test2_ds1"),
                  fullRemoteDataStreamWithAliases("es_pl", "test1_ds1"),
                  fullRemoteDataStreamWithAliases("es_pl", "test1_ds2"),
                  fullRemoteDataStreamWithAliases("es_pl", "test2_ds1"),
                )
              )
            )
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
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set.empty,
                allRemoteDataStreamsAndAliases = Set(fullRemoteDataStreamWithAliases("es_us", "test1_ds")),
                allRemoteClusterNames = Set(clusterName("es_us"))
              )
            )
          ),
          filteredRequestedIndices = Set(
            requestedIndex("es_us:.ds-test1_ds"),
          ),
          allAllowedClusters = Set(
            clusterName("es_us")
          )
        )
      }
      "multi filterable request tries to fetch data for allowed and not allowed data streams" in {
        assertMatchRuleForMultiIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds")),
          indexPacks = Indices.Found(Set(requestedIndex("test1_ds"), requestedIndex("test2_ds"))) :: Nil,
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = testDataStreams
              )
            )
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
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set.empty
              )
            )
          ),
        )
      }
      "'_all' passed, one is configured, no real data streams" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test_ds")),
          requestIndices = Set(requestedIndex("_all")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set.empty
              )
            )
          ),
        )
      }
      "'*' passed, one is configured, no real data streams" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test_ds")),
          requestIndices = Set(requestedIndex("*")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set.empty
              )
            )
          ),
        )
      }
      "one full name data stream passed, different one full name data stream configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds")),
          requestIndices = Set(requestedIndex("test2_ds")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = testDataStreams
              )
            )
          ),
        )
      }
      "one wildcard data stream passed, non-matching data stream with full name configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds")),
          requestIndices = Set(requestedIndex("*2")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = testDataStreams
              )
            )
          ),
        )
      }
      "one full name data stream passed, non-matching data stream with wildcard configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*1")),
          requestIndices = Set(requestedIndex("test2_ds")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = testDataStreams
              )
            )
          ),
        )
      }
      "two full name data streams passed, different two full name data streams configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds"), indexNameVar("test2_ds")),
          requestIndices = Set(requestedIndex("test4_ds"), requestedIndex("test3_ds")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = testDataStreams
              )
            )
          ),
        )
      }
      "two wildcard data streams passed, non-matching two full name data streams configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds"), indexNameVar("test2_ds")),
          requestIndices = Set(requestedIndex("*4"), requestedIndex("*3")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allDataStreamsAndAliases = testDataStreams
              )
            )
          ),
        )
      }
      "two full name data streams passed, non-matching two wildcard data streams configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*1"), indexNameVar("*2")),
          requestIndices = Set(requestedIndex("test4_ds"), requestedIndex("test3_ds")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = testDataStreams
              )
            )
          ),
        )
      }
      "one full name alias passed, full name data stream with no alias configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds")),
          requestIndices = Set(requestedIndex("test_alias")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(
                  fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds")),
                  fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"), Set(fullDataStreamName("test_alias")))
                )
              )
            )
          )
        )
      }
      "one data stream name passed, full name data stream alias configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test_alias")),
          requestIndices = Set(requestedIndex("test2_ds")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(
                  fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds")),
                  fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"), Set(fullDataStreamName("test_alias")))
                )
              )
            )
          )
        )
      }
      "wildcard alias passed, full name data stream with no alias configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_ds")),
          requestIndices = Set(requestedIndex("*_alias")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(
                  fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds"), Set.empty),
                  fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds"), Set(fullDataStreamName("test_alias")))
                )
              )
            )
          )
        )
      }
      "full name data stream passed, data stream alias configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test12_alias")),
          requestIndices = Set(requestedIndex("test1_ds")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(
                  fullLocalDataStreamWithAliases(
                    fullDataStreamName("test1_ds"),
                    Set(fullDataStreamName("test12_alias"))
                  ),
                  fullLocalDataStreamWithAliases(
                    fullDataStreamName("test2_ds"),
                    Set(fullDataStreamName("test12_alias"))
                  ),
                  fullLocalDataStreamWithAliases(
                    fullDataStreamName("test3_ds"),
                    Set(fullDataStreamName("test34_alias"))
                  ),
                  fullLocalDataStreamWithAliases(
                    fullDataStreamName("test4_ds"),
                    Set(fullDataStreamName("test34_alias"))
                  )
                )
              )
            )
          )
        )
      }
      "one backing index passed, one full data stream alias configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test_alias1")),
          requestIndices = Set(requestedIndex(".ds-test1_ds")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(
                  fullLocalDataStreamWithAliases(
                    fullDataStreamName("test1_ds"),
                    Set(fullDataStreamName("test1_alias"))
                  ),
                  fullLocalDataStreamWithAliases(
                    fullDataStreamName("test2_ds"),
                    Set(fullDataStreamName("test1_alias"))
                  ),
                  fullLocalDataStreamWithAliases(
                    fullDataStreamName("test3_ds"),
                    Set(fullDataStreamName("test2_alias"))
                  ),
                  fullLocalDataStreamWithAliases(fullDataStreamName("test4_ds"), Set(fullDataStreamName("test2_alias")))
                )
              )
            )
          )
        )
      }
      "one backing index passed, one data stream alias pattern configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_al*")),
          requestIndices = Set(requestedIndex(".ds-test1_ds")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(
                  fullLocalDataStreamWithAliases(
                    fullDataStreamName("test1_ds"),
                    Set(fullDataStreamName("test1_alias"))
                  ),
                  fullLocalDataStreamWithAliases(
                    fullDataStreamName("test2_ds"),
                    Set(fullDataStreamName("test1_alias"))
                  ),
                  fullLocalDataStreamWithAliases(
                    fullDataStreamName("test3_ds"),
                    Set(fullDataStreamName("test2_alias"))
                  ),
                  fullLocalDataStreamWithAliases(fullDataStreamName("test4_ds"), Set(fullDataStreamName("test2_alias")))
                )
              )
            )
          )
        )
      }
      "one backing index pattern passed, one full data stream alias configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1_alias")),
          requestIndices = Set(requestedIndex(".ds-test*")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(
                  fullLocalDataStreamWithAliases(
                    fullDataStreamName("test1_ds"),
                    Set(fullDataStreamName("test1_alias"))
                  ),
                  fullLocalDataStreamWithAliases(
                    fullDataStreamName("test2_ds"),
                    Set(fullDataStreamName("test1_alias"))
                  ),
                  fullLocalDataStreamWithAliases(
                    fullDataStreamName("test3_ds"),
                    Set(fullDataStreamName("test2_alias"))
                  ),
                  fullLocalDataStreamWithAliases(fullDataStreamName("test4_ds"), Set(fullDataStreamName("test2_alias")))
                )
              )
            )
          )
        )
      }
      "one backing index pattern passed, one data stream alias pattern configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test_al*")),
          requestIndices = Set(requestedIndex(".ds-test*")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(
                  fullLocalDataStreamWithAliases(
                    fullDataStreamName("test1_ds"),
                    Set(fullDataStreamName("test1_alias"))
                  ),
                  fullLocalDataStreamWithAliases(
                    fullDataStreamName("test2_ds"),
                    Set(fullDataStreamName("test1_alias"))
                  ),
                  fullLocalDataStreamWithAliases(
                    fullDataStreamName("test3_ds"),
                    Set(fullDataStreamName("test2_alias"))
                  ),
                  fullLocalDataStreamWithAliases(fullDataStreamName("test4_ds"), Set(fullDataStreamName("test2_alias")))
                )
              )
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
            esServices = Some(
              MockEsServices.`with`(
                MockEsClusterService(
                  allIndicesAndAliases = Set.empty,
                  allDataStreamsAndAliases =
                    Set(fullLocalDataStreamWithAliases(fullDataStreamName("test_ds"), Set.empty)),
                  allRemoteDataStreamsAndAliases = Set(
                    fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-27"),
                    fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-28"),
                    fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-29"),
                    fullRemoteDataStreamWithAliases("es_uk", "other-index"),
                    fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-27"),
                    fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-28"),
                    fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-29")
                  ),
                  allRemoteClusterNames = Set(clusterName("es_us"), clusterName("es_uk"), clusterName("es_pl"))
                )
              )
            ),
            filteredRequestedIndices = Set(
              requestedIndex("es_us:c01-logs-2020-03-27"),
              requestedIndex("es_us:c01-logs-2020-03-28"),
              requestedIndex("es_us:c01-logs-2020-03-29")
            ),
            allAllowedClusters = Set(clusterName("es_us"), clusterName("es_uk"))
          )
        }
        "requested data stream name with wildcard is more general version of the configured data stream name with wildcard" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("es_u*:*-logs-*")),
            requestIndices = Set(requestedIndex("e*:*-logs-*")),
            esServices = Some(
              MockEsServices.`with`(
                MockEsClusterService(
                  allIndicesAndAliases = Set.empty,
                  allDataStreamsAndAliases =
                    Set(fullLocalDataStreamWithAliases(fullDataStreamName("test_ds"), Set.empty)),
                  allRemoteDataStreamsAndAliases = Set(
                    fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-27"),
                    fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-28"),
                    fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-29"),
                    fullRemoteDataStreamWithAliases("es_uk", "other-index"),
                    fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-27"),
                    fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-28"),
                    fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-29")
                  ),
                  allRemoteClusterNames = Set(clusterName("es_us"), clusterName("es_uk"), clusterName("es_pl"))
                )
              )
            ),
            filteredRequestedIndices = Set(
              requestedIndex("es_us:c01-logs-2020-03-27"),
              requestedIndex("es_us:c01-logs-2020-03-28"),
              requestedIndex("es_us:c01-logs-2020-03-29")
            ),
            allAllowedClusters = Set(clusterName("es_us"), clusterName("es_uk"))
          )
        }
        "requested data stream name with wildcard is more specialized version of the configured data stream name with wildcard" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("es_u*:*-logs-*")),
            requestIndices = Set(requestedIndex("e*:*-logs-2020-03-2*")),
            esServices = Some(
              MockEsServices.`with`(
                MockEsClusterService(
                  allIndicesAndAliases = Set.empty,
                  allDataStreamsAndAliases =
                    Set(fullLocalDataStreamWithAliases(fullDataStreamName("test_ds"), Set.empty)),
                  allRemoteDataStreamsAndAliases = Set(
                    fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-27"),
                    fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-28"),
                    fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-29"),
                    fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-30"),
                    fullRemoteDataStreamWithAliases("es_uk", "other-index"),
                    fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-27"),
                    fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-28"),
                    fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-29")
                  ),
                  allRemoteClusterNames = Set(clusterName("es_us"), clusterName("es_uk"), clusterName("es_pl"))
                )
              )
            ),
            filteredRequestedIndices = Set(
              requestedIndex("es_us:c01-logs-2020-03-27"),
              requestedIndex("es_us:c01-logs-2020-03-28"),
              requestedIndex("es_us:c01-logs-2020-03-29")
            ),
            allAllowedClusters = Set(clusterName("es_us"), clusterName("es_uk"))
          )
        }
        "requested data stream name with wildcard doesn't match the configured data stream name with wildcard but it does match the resolved data stream name" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("es_u*:*-logs-*")),
            requestIndices = Set(requestedIndex("e*:c0*")),
            esServices = Some(
              MockEsServices.`with`(
                MockEsClusterService(
                  allIndicesAndAliases = Set.empty,
                  allDataStreamsAndAliases =
                    Set(fullLocalDataStreamWithAliases(fullDataStreamName("test_ds"), Set.empty)),
                  allRemoteDataStreamsAndAliases = Set(
                    fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-27"),
                    fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-28"),
                    fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-29"),
                    fullRemoteDataStreamWithAliases("es_uk", "other-index"),
                    fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-27"),
                    fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-28"),
                    fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-29")
                  ),
                  allRemoteClusterNames = Set(clusterName("es_us"), clusterName("es_uk"), clusterName("es_pl"))
                )
              )
            ),
            filteredRequestedIndices = Set(
              requestedIndex("es_us:c01-logs-2020-03-27"),
              requestedIndex("es_us:c01-logs-2020-03-28"),
              requestedIndex("es_us:c01-logs-2020-03-29")
            ),
            allAllowedClusters = Set(clusterName("es_us"), clusterName("es_uk"))
          )
        }
        "requested data stream alias pattern when data stream configured" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("*:test1_ds")),
            requestIndices = Set(requestedIndex("es_us:test1_al*")),
            esServices = Some(
              MockEsServices.`with`(
                MockEsClusterService(
                  allIndicesAndAliases = Set.empty,
                  allDataStreamsAndAliases =
                    Set(fullLocalDataStreamWithAliases(fullDataStreamName("test_ds"), Set.empty)),
                  allRemoteDataStreamsAndAliases = Set(
                    fullRemoteDataStreamWithAliases("es_us", "test1_ds", "test1_alias"),
                    fullRemoteDataStreamWithAliases("es_us", "test2_ds", "test10_alias"),
                    fullRemoteDataStreamWithAliases("es_us", "test3_ds", "test2_alias"),
                  ),
                  allRemoteClusterNames = Set(clusterName("es_us"))
                )
              )
            ),
            filteredRequestedIndices = Set(
              requestedIndex("es_us:test1_ds")
            ),
            allAllowedClusters = Set(clusterName("es_us"))
          )
        }
        "requested data stream alias when data stream configured" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("*:test_ds")),
            requestIndices = Set(requestedIndex("es_us:test_alias")),
            esServices = Some(
              MockEsServices.`with`(
                MockEsClusterService(
                  allIndicesAndAliases = Set.empty,
                  allDataStreamsAndAliases =
                    Set(fullLocalDataStreamWithAliases(fullDataStreamName("test_ds"), Set.empty)),
                  allRemoteDataStreamsAndAliases = Set(
                    fullRemoteDataStreamWithAliases("es_us", "test_ds", "test_alias"),
                    fullRemoteDataStreamWithAliases("es_us", "test1_ds", "test_alias"),
                    fullRemoteDataStreamWithAliases("es_us", "test2_ds", "test_alias"),
                  ),
                  allRemoteClusterNames = Set(clusterName("es_us"))
                )
              )
            ),
            filteredRequestedIndices = Set(requestedIndex("es_us:test_ds")),
            allAllowedClusters = Set(clusterName("es_us"))
          )
        }
        "requested data stream alias when data stream alias configured" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("*:test_alias")),
            requestIndices = Set(requestedIndex("es_us:test_alias")),
            esServices = Some(
              MockEsServices.`with`(
                MockEsClusterService(
                  allIndicesAndAliases = Set.empty,
                  allDataStreamsAndAliases =
                    Set(fullLocalDataStreamWithAliases(fullDataStreamName("test_ds"), Set.empty)),
                  allRemoteDataStreamsAndAliases = Set(
                    fullRemoteDataStreamWithAliases("es_us", "test_ds", "test_alias"),
                    fullRemoteDataStreamWithAliases("es_us", "test1_ds", "test_alias"),
                    fullRemoteDataStreamWithAliases("es_us", "test2_ds", "test_alias"),
                  ),
                  allRemoteClusterNames = Set(clusterName("es_us"))
                )
              )
            ),
            filteredRequestedIndices = Set(
              requestedIndex("es_us:test_alias")
            ),
            allAllowedClusters = Set(clusterName("es_us"))
          )
        }
        "requested data stream alias pattern when data stream alias configured" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("*:test_alias")),
            requestIndices = Set(requestedIndex("es_us:test_al*")),
            esServices = Some(
              MockEsServices.`with`(
                MockEsClusterService(
                  allIndicesAndAliases = Set.empty,
                  allDataStreamsAndAliases =
                    Set(fullLocalDataStreamWithAliases(fullDataStreamName("test_ds"), Set.empty)),
                  allRemoteDataStreamsAndAliases = Set(
                    fullRemoteDataStreamWithAliases("es_us", "test1_ds", "test_alias"),
                    fullRemoteDataStreamWithAliases("es_us", "test2_ds", "test_alias"),
                  ),
                  allRemoteClusterNames = Set(clusterName("es_us"))
                )
              )
            ),
            filteredRequestedIndices = Set(requestedIndex("es_us:test_alias")),
            allAllowedClusters = Set(clusterName("es_us"))
          )
        }
      }
    }
    "not match" when {
      "not allowed cluster data streams are being called" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*-logs-*"), indexNameVar("es_u*:*-logs-*")),
          requestIndices = Set(requestedIndex("pub*:*logs*")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set(
                  fullLocalDataStreamWithAliases(fullDataStreamName("clocal-logs-2020-03-27")),
                  fullLocalDataStreamWithAliases(fullDataStreamName("clocal-logs-2020-03-28")),
                  fullLocalDataStreamWithAliases(fullDataStreamName("clocal-logs-2020-03-29")),
                ),
                allRemoteDataStreamsAndAliases = Set(
                  fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-27"),
                  fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-28"),
                  fullRemoteDataStreamWithAliases("es_us", "c01-logs-2020-03-29"),
                  fullRemoteDataStreamWithAliases("es_us", "other-index"),
                  fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-27"),
                  fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-28"),
                  fullRemoteDataStreamWithAliases("es_pl", "c02-logs-2020-03-29")
                ),
                allRemoteClusterNames = Set(clusterName("es_us"), clusterName("es_uk"), clusterName("es_pl"))
              )
            )
          ),
          allAllowedClusters = Set(clusterName("es_us"), clusterName("es_uk"), clusterName("(local)"))
        )
      }
      "cross cluster data stream backing index is used when data stream alias configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*:test_alias")),
          requestIndices = Set(requestedIndex("es_us:.ds-test1_ds")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set.empty,
                allDataStreamsAndAliases = Set.empty,
                allRemoteDataStreamsAndAliases = Set(
                  fullRemoteDataStreamWithAliases("es_us", "test1_ds", "test_alias"),
                ),
                allRemoteClusterNames = Set(clusterName("es_us"))
              )
            )
          ),
          allAllowedClusters = Set(clusterName("es_us"))
        )
      }
    }
  }

  "An IndicesRule for data stream API requests" should {
    "match" when {
      "the data stream name matches the allowed indices" in {
        dataStreamWriteActions.foreach { action =>
          assertMatchRuleForDataStreamRequest(
            configured = NonEmptySet.of(indexNameVar("logs-*")),
            action = action,
            requestDataStreams = Set(fullDataStreamName("logs-app"))
          )
        }
      }
      "all data stream names match the allowed indices" in {
        assertMatchRuleForDataStreamRequest(
          configured = NonEmptySet.of(indexNameVar("logs-*"), indexNameVar("metrics-app")),
          action = deleteDataStreamAction,
          requestDataStreams = Set(fullDataStreamName("logs-app"), fullDataStreamName("metrics-app"))
        )
      }
      "all indices are allowed" in {
        assertMatchRuleForDataStreamRequest(
          configured = NonEmptySet.of(indexNameVar("*")),
          action = deleteDataStreamAction,
          requestDataStreams = Set(fullDataStreamName("audit-events"))
        )
      }
      "the data stream is the Kibana reporting data stream" in {
        assertMatchRuleForDataStreamRequest(
          configured = NonEmptySet.of(indexNameVar("logs-*")),
          action = deleteDataStreamAction,
          requestDataStreams = Set(fullDataStreamName(".kibana-reporting-.kibana")),
          kibanaIndex = Some(kibanaIndexName(".kibana"))
        )
      }
    }
    "not match" when {
      "the data stream name does not match the allowed indices" in {
        dataStreamWriteActions.foreach { action =>
          assertNotMatchRuleForDataStreamRequest(
            configured = NonEmptySet.of(indexNameVar("logs-*")),
            action = action,
            requestDataStreams = Set(fullDataStreamName("audit-events"))
          )
        }
      }
      "one of the data stream names does not match the allowed indices" in {
        assertNotMatchRuleForDataStreamRequest(
          configured = NonEmptySet.of(indexNameVar("logs-*")),
          action = deleteDataStreamAction,
          requestDataStreams = Set(fullDataStreamName("logs-app"), fullDataStreamName("audit-events"))
        )
      }
      "the request has no data stream names and the rule does not allow all data streams" in {
        dataStreamWriteActions.foreach { action =>
          checkDataStreamWriteRequest(
            configured = NonEmptySet.of(indexNameVar("logs-*")),
            action = action,
            requestDataStreams = Set.empty,
            clusterDataStreams = statsClusterDataStreams
          ) should be(Denied(NotAuthorized))
        }
      }
      "the data stream name is a pattern that matches a data stream that the rule does not allow" in {
        List(dataStreamNamePattern("logs-*"), DataStreamName.Wildcard, DataStreamName.All).foreach { dataStream =>
          checkDataStreamWriteRequest(
            configured = NonEmptySet.of(indexNameVar("logs-app")),
            action = deleteDataStreamAction,
            requestDataStreams = Set(dataStream),
            clusterDataStreams = statsClusterDataStreams
          ) should be(Denied(NotAuthorized))
        }
      }
      "only the backing indices of the data stream are allowed" in {
        assertNotMatchRuleForDataStreamRequest(
          configured = NonEmptySet.of(indexNameVar(".ds-logs-app*")),
          action = deleteDataStreamAction,
          requestDataStreams = Set(fullDataStreamName("logs-app"))
        )
      }
      "the rule allows only the indices of the remote clusters" in {
        assertNotMatchRuleForDataStreamRequest(
          configured = NonEmptySet.of(indexNameVar("*:*")),
          action = deleteDataStreamAction,
          requestDataStreams = Set(fullDataStreamName("logs-app"))
        )
      }
      "the data stream is the Kibana reporting data stream and the block has no Kibana index" in {
        assertNotMatchRuleForDataStreamRequest(
          configured = NonEmptySet.of(indexNameVar("logs-*")),
          action = deleteDataStreamAction,
          requestDataStreams = Set(fullDataStreamName(".kibana-reporting-.kibana"))
        )
      }
      "the indices must be involved, also when the data stream name matches the allowed indices" in {
        dataStreamWriteActions.foreach { action =>
          assertNotMatchRuleForDataStreamRequest(
            configured = NonEmptySet.of(indexNameVar("logs-*")),
            action = action,
            requestDataStreams = Set(fullDataStreamName("logs-app")),
            mustInvolveIndices = true
          )
        }
      }
    }
  }

  "An IndicesRule for a data stream delete request with a pattern" should {
    "get the names of the matched data streams" when {
      "the pattern is the allowed indices pattern" in {
        val result = checkDataStreamWriteRequest(
          configured = NonEmptySet.of(indexNameVar("logs-*")),
          action = deleteDataStreamAction,
          requestDataStreams = Set(dataStreamNamePattern("logs-*")),
          clusterDataStreams = statsClusterDataStreams
        )
        inside(result) { case Permitted(blockContext) =>
          blockContext.dataStreams should be(Set(fullDataStreamName("logs-app"), fullDataStreamName("logs-db")))
        }
      }
      "the allowed indices pattern matches the text of the requested pattern and all matched data streams" in {
        val result = checkDataStreamWriteRequest(
          configured = NonEmptySet.of(indexNameVar("logs-?")),
          action = deleteDataStreamAction,
          requestDataStreams = Set(dataStreamNamePattern("logs-*")),
          clusterDataStreams = Set(fullDataStreamName("logs-a"), fullDataStreamName("audit-events"))
        )
        inside(result) { case Permitted(blockContext) =>
          blockContext.dataStreams should be(Set(fullDataStreamName("logs-a")))
        }
      }
      "the request has a full name and a pattern" in {
        val result = checkDataStreamWriteRequest(
          configured = NonEmptySet.of(indexNameVar("logs-*"), indexNameVar("audit-events")),
          action = deleteDataStreamAction,
          requestDataStreams = Set(fullDataStreamName("audit-events"), dataStreamNamePattern("logs-a*")),
          clusterDataStreams = statsClusterDataStreams
        )
        inside(result) { case Permitted(blockContext) =>
          blockContext.dataStreams should be(Set(fullDataStreamName("audit-events"), fullDataStreamName("logs-app")))
        }
      }
      "the rule allows the only matched data stream, but not the text of the pattern" in {
        val result = checkDataStreamWriteRequest(
          configured = NonEmptySet.of(indexNameVar("logs-2024")),
          action = deleteDataStreamAction,
          requestDataStreams = Set(dataStreamNamePattern("logs-2024*")),
          clusterDataStreams = Set(fullDataStreamName("logs-2024"), fullDataStreamName("audit-events"))
        )
        inside(result) { case Permitted(blockContext) =>
          blockContext.dataStreams should be(Set(fullDataStreamName("logs-2024")))
        }
      }
      "the rule allows each matched data stream by its full name" in {
        val result = checkDataStreamWriteRequest(
          configured = NonEmptySet.of(indexNameVar("logs-app"), indexNameVar("logs-db")),
          action = deleteDataStreamAction,
          requestDataStreams = Set(dataStreamNamePattern("logs-*")),
          clusterDataStreams = Set(fullDataStreamName("logs-app"), fullDataStreamName("logs-db"))
        )
        inside(result) { case Permitted(blockContext) =>
          blockContext.dataStreams should be(Set(fullDataStreamName("logs-app"), fullDataStreamName("logs-db")))
        }
      }
      "the request asks for all data streams and the rule allows each of them" in {
        List(DataStreamName.All, DataStreamName.Wildcard).foreach { dataStream =>
          val result = checkDataStreamWriteRequest(
            configured = NonEmptySet.of(indexNameVar("logs-*")),
            action = deleteDataStreamAction,
            requestDataStreams = Set(dataStream),
            clusterDataStreams = Set(fullDataStreamName("logs-app"), fullDataStreamName("logs-db"))
          )
          inside(result) { case Permitted(blockContext) =>
            blockContext.dataStreams should be(Set(fullDataStreamName("logs-app"), fullDataStreamName("logs-db")))
          }
        }
      }
      "the request excludes a data stream" in {
        val result = checkDataStreamWriteRequest(
          configured = NonEmptySet.of(indexNameVar("logs-*")),
          action = deleteDataStreamAction,
          requestDataStreams = Set(dataStreamNamePattern("logs-*"), fullDataStreamName("-logs-db")),
          clusterDataStreams = statsClusterDataStreams
        )
        inside(result) { case Permitted(blockContext) =>
          blockContext.dataStreams should be(Set(fullDataStreamName("logs-app")))
        }
      }
      "the request excludes a data stream that is not allowed" in {
        val result = checkDataStreamWriteRequest(
          configured = NonEmptySet.of(indexNameVar("logs-?")),
          action = deleteDataStreamAction,
          requestDataStreams = Set(dataStreamNamePattern("logs-*"), dataStreamNamePattern("-logs-s*")),
          clusterDataStreams = Set(fullDataStreamName("logs-a"), fullDataStreamName("logs-secret"))
        )
        inside(result) { case Permitted(blockContext) =>
          blockContext.dataStreams should be(Set(fullDataStreamName("logs-a")))
        }
      }
    }
    "get a name that matches nothing" when {
      "the request excludes all matched data streams" in {
        val result = checkDataStreamWriteRequest(
          configured = NonEmptySet.of(indexNameVar("logs-*")),
          action = deleteDataStreamAction,
          requestDataStreams = Set(dataStreamNamePattern("logs-*"), dataStreamNamePattern("-logs-*")),
          clusterDataStreams = statsClusterDataStreams
        )
        inside(result) { case Permitted(blockContext) =>
          inside(blockContext.dataStreams.toList) { case (pattern: DataStreamName.Pattern) :: Nil =>
            val patternMatcher = PatternsMatcher.create[DataStreamName](Set(pattern))
            statsClusterDataStreams.filter(patternMatcher.`match`) should be(empty)
          }
        }
      }
      "the pattern matches no data stream" in {
        val result = checkDataStreamWriteRequest(
          configured = NonEmptySet.of(indexNameVar("metrics-*")),
          action = deleteDataStreamAction,
          requestDataStreams = Set(dataStreamNamePattern("metrics-*")),
          clusterDataStreams = statsClusterDataStreams
        )
        inside(result) { case Permitted(blockContext) =>
          inside(blockContext.dataStreams.toList) { case (pattern: DataStreamName.Pattern) :: Nil =>
            val patternMatcher = PatternsMatcher.create[DataStreamName](Set(pattern))
            statsClusterDataStreams.filter(patternMatcher.`match`) should be(empty)
          }
        }
      }
    }
    "not match" when {
      "the request has an exclusion but no pattern" in {
        val result = checkDataStreamWriteRequest(
          configured = NonEmptySet.of(indexNameVar("logs-*")),
          action = deleteDataStreamAction,
          requestDataStreams = Set(fullDataStreamName("logs-app"), fullDataStreamName("-logs-db")),
          clusterDataStreams = statsClusterDataStreams
        )
        result should be(Denied(NotAuthorized))
      }
      "the request asks for all data streams and the rule does not allow one of them" in {
        val result = checkDataStreamWriteRequest(
          configured = NonEmptySet.of(indexNameVar("logs-*")),
          action = deleteDataStreamAction,
          requestDataStreams = Set(DataStreamName.All),
          clusterDataStreams = statsClusterDataStreams
        )
        result should be(Denied(NotAuthorized))
      }
      "the pattern matches a data stream that is not allowed" in {
        val result = checkDataStreamWriteRequest(
          configured = NonEmptySet.of(indexNameVar("logs-?")),
          action = deleteDataStreamAction,
          requestDataStreams = Set(dataStreamNamePattern("logs-*")),
          clusterDataStreams = Set(fullDataStreamName("logs-a"), fullDataStreamName("logs-secret"))
        )
        result should be(Denied(NotAuthorized))
      }
    }
  }

  "An IndicesRule for a data stream stats request" should {
    "leave only the allowed data streams" when {
      "the request has no data stream names" in {
        assertDataStreamStatsRequestNarrowed(
          configured = NonEmptySet.of(indexNameVar("logs-*")),
          requestDataStreams = Set.empty,
          clusterDataStreams = statsClusterDataStreams,
          expectedDataStreams = Set(fullDataStreamName("logs-app"), fullDataStreamName("logs-db"))
        )
      }
      "the request asks for all data streams" in {
        List(DataStreamName.All, DataStreamName.Wildcard).foreach { dataStream =>
          assertDataStreamStatsRequestNarrowed(
            configured = NonEmptySet.of(indexNameVar("logs-*")),
            requestDataStreams = Set(dataStream),
            clusterDataStreams = statsClusterDataStreams,
            expectedDataStreams = Set(fullDataStreamName("logs-app"), fullDataStreamName("logs-db"))
          )
        }
      }
      "the request has a pattern wider than the allowed indices" in {
        assertDataStreamStatsRequestNarrowed(
          configured = NonEmptySet.of(indexNameVar("logs-app")),
          requestDataStreams = Set(dataStreamNamePattern("logs-*")),
          clusterDataStreams = statsClusterDataStreams,
          expectedDataStreams = Set(fullDataStreamName("logs-app"))
        )
      }
      "the request has an allowed and a not allowed name" in {
        assertDataStreamStatsRequestNarrowed(
          configured = NonEmptySet.of(indexNameVar("logs-*")),
          requestDataStreams = Set(fullDataStreamName("logs-app"), fullDataStreamName("audit-events")),
          clusterDataStreams = statsClusterDataStreams,
          expectedDataStreams = Set(fullDataStreamName("logs-app"))
        )
      }
      "the rule allows all backing indices of the data stream" in {
        assertDataStreamStatsRequestNarrowed(
          configured = NonEmptySet.of(indexNameVar(".ds-audit-*")),
          requestDataStreams = Set.empty,
          clusterDataStreams = statsClusterDataStreams,
          expectedDataStreams = Set(fullDataStreamName("audit-events"))
        )
      }
      "the data stream is the Kibana reporting data stream" in {
        assertDataStreamStatsRequestNarrowed(
          configured = NonEmptySet.of(indexNameVar("logs-app")),
          requestDataStreams = Set.empty,
          clusterDataStreams = statsClusterDataStreams + fullDataStreamName(".kibana-reporting-.kibana"),
          expectedDataStreams = Set(fullDataStreamName("logs-app"), fullDataStreamName(".kibana-reporting-.kibana")),
          kibanaIndex = Some(kibanaIndexName(".kibana"))
        )
      }
      "the requested data stream does not exist and the rule allows its name" in {
        assertDataStreamStatsRequestNarrowed(
          configured = NonEmptySet.of(indexNameVar("logs-*")),
          requestDataStreams = Set(fullDataStreamName("logs-unknown")),
          clusterDataStreams = statsClusterDataStreams,
          expectedDataStreams = Set(fullDataStreamName("logs-unknown"))
        )
      }
      "the request has a pattern and an allowed name of a data stream that does not exist" in {
        assertDataStreamStatsRequestNarrowed(
          configured = NonEmptySet.of(indexNameVar("logs-*")),
          requestDataStreams = Set(dataStreamNamePattern("logs-*"), fullDataStreamName("logs-unknown")),
          clusterDataStreams = statsClusterDataStreams,
          expectedDataStreams =
            Set(fullDataStreamName("logs-app"), fullDataStreamName("logs-db"), fullDataStreamName("logs-unknown"))
        )
      }
      "the request excludes a data stream" in {
        assertDataStreamStatsRequestNarrowed(
          configured = NonEmptySet.of(indexNameVar("logs-*")),
          requestDataStreams = Set(dataStreamNamePattern("logs-*"), fullDataStreamName("-logs-db")),
          clusterDataStreams = statsClusterDataStreams,
          expectedDataStreams = Set(fullDataStreamName("logs-app"))
        )
      }
      "the request excludes a data stream that the requested pattern matches" in {
        assertDataStreamStatsRequestNarrowed(
          configured = NonEmptySet.of(indexNameVar("logs-*")),
          requestDataStreams = Set(dataStreamNamePattern("logs-2*"), fullDataStreamName("-logs-2c")),
          clusterDataStreams =
            Set(fullDataStreamName("logs-2a"), fullDataStreamName("logs-2b"), fullDataStreamName("logs-2c")),
          expectedDataStreams = Set(fullDataStreamName("logs-2a"), fullDataStreamName("logs-2b"))
        )
      }
      "the request excludes with a pattern a part of the data streams that the requested pattern matches" in {
        assertDataStreamStatsRequestNarrowed(
          configured = NonEmptySet.of(indexNameVar("logs-*")),
          requestDataStreams = Set(dataStreamNamePattern("logs-2*"), dataStreamNamePattern("-logs-22*")),
          clusterDataStreams = Set(
            fullDataStreamName("logs-2a"),
            fullDataStreamName("logs-2b"),
            fullDataStreamName("logs-22a"),
            fullDataStreamName("logs22b")
          ),
          expectedDataStreams = Set(fullDataStreamName("logs-2a"), fullDataStreamName("logs-2b"))
        )
      }
      "the request excludes data streams with a pattern" in {
        assertDataStreamStatsRequestNarrowed(
          configured = NonEmptySet.of(indexNameVar("*-*")),
          requestDataStreams = Set(DataStreamName.All, dataStreamNamePattern("-logs-*")),
          clusterDataStreams = statsClusterDataStreams,
          expectedDataStreams = Set(fullDataStreamName("audit-events"))
        )
      }
    }
    "leave a data stream name that matches nothing" when {
      "the rule allows none of the requested data streams" in {
        assertDataStreamStatsRequestGetsNoDataStream(
          configured = NonEmptySet.of(indexNameVar("logs-*")),
          requestDataStreams = Set(fullDataStreamName("audit-events")),
          clusterDataStreams = statsClusterDataStreams
        )
      }
      "the requested data stream does not exist and the rule does not allow its name" in {
        assertDataStreamStatsRequestGetsNoDataStream(
          configured = NonEmptySet.of(indexNameVar("logs-*")),
          requestDataStreams = Set(fullDataStreamName("audit-unknown")),
          clusterDataStreams = statsClusterDataStreams
        )
      }
      "the requested pattern matches no data stream" in {
        assertDataStreamStatsRequestGetsNoDataStream(
          configured = NonEmptySet.of(indexNameVar("logs-*")),
          requestDataStreams = Set(dataStreamNamePattern("logs-unknown-*")),
          clusterDataStreams = statsClusterDataStreams
        )
      }
    }
    "not match" when {
      "the indices must be involved" in {
        assertDataStreamStatsRequestRejected(
          configured = NonEmptySet.of(indexNameVar("logs-*")),
          requestDataStreams = Set(fullDataStreamName("logs-app")),
          clusterDataStreams = statsClusterDataStreams,
          mustInvolveIndices = true
        )
      }
    }
  }

  "An IndicesRule for a data stream modify request" should {
    "match" when {
      "the index and the data stream match the allowed indices" in {
        inside(modifyRequest(dataStream = "logs-app", index = "logs-x")) { case Permitted(_) => }
      }
      "the rule allows all backing indices of the data stream" in {
        inside(
          checkDataStreamRequestWithIndices(
            configured = NonEmptySet.of(indexNameVar("logs-*"), indexNameVar(".ds-audit-*")),
            action = modifyDataStreamsAction,
            requestDataStreams = Set(fullDataStreamName("audit-events")),
            requestIndices = Set(requestedIndex("logs-x")),
            clusterDataStreams = statsClusterDataStreams
          )
        ) { case Permitted(_) => }
      }
    }
    "not match" when {
      "an allowed index is added to a data stream that is not allowed" in {
        modifyRequest(dataStream = "audit-events", index = "logs-x") should be(Denied(NotAuthorized))
      }
      "the data stream name is a pattern" in {
        modifyRequest(dataStream = "logs-*", index = "logs-x") should be(Denied(NotAuthorized))
      }
      "the index is not allowed" in {
        modifyRequest(dataStream = "logs-app", index = "audit-x") should be(Denied(NotAuthorized))
      }
    }
  }

  "An IndicesRule for a data stream migrate request" should {
    "match" when {
      "the alias, which is the new data stream name, matches the allowed indices" in {
        inside(migrateRequest(alias = "logs-alias")) { case Permitted(_) => }
      }
    }
    "not match" when {
      "the alias does not match the allowed indices" in {
        migrateRequest(alias = "audit-alias") should be(Denied(NotAuthorized))
      }
    }
  }

  "An IndicesRule for a data stream read request with indices" should {
    "leave only the allowed data streams" when {
      "the request asks for all data streams" in {
        List(getDataStreamAction, getDataStreamLifecycleAction).foreach { action =>
          val result = checkDataStreamRequestWithIndices(
            configured = NonEmptySet.of(indexNameVar("logs-*")),
            action = action,
            requestDataStreams = Set.empty,
            requestIndices = Set.empty,
            clusterDataStreams = statsClusterDataStreams
          )
          inside(result) { case Permitted(blockContext) =>
            blockContext.dataStreams should be(Set(fullDataStreamName("logs-app"), fullDataStreamName("logs-db")))
          }
        }
      }
      "the request asks for a data stream that is not allowed" in {
        List(getDataStreamAction, getDataStreamLifecycleAction).foreach { action =>
          val result = checkDataStreamRequestWithIndices(
            configured = NonEmptySet.of(indexNameVar("logs-*")),
            action = action,
            requestDataStreams = Set(dataStreamNamePattern("audit-*"), fullDataStreamName("logs-app")),
            requestIndices = Set.empty,
            clusterDataStreams = statsClusterDataStreams
          )
          inside(result) { case Permitted(blockContext) =>
            blockContext.dataStreams should be(Set(fullDataStreamName("logs-app")))
          }
        }
      }
      "the requested data stream does not exist and the rule allows its name" in {
        List(getDataStreamAction, getDataStreamLifecycleAction).foreach { action =>
          val result = checkDataStreamRequestWithIndices(
            configured = NonEmptySet.of(indexNameVar("logs-*")),
            action = action,
            requestDataStreams = Set(fullDataStreamName("logs-unknown")),
            requestIndices = Set.empty,
            clusterDataStreams = statsClusterDataStreams
          )
          inside(result) { case Permitted(blockContext) =>
            blockContext.dataStreams should be(Set(fullDataStreamName("logs-unknown")))
          }
        }
      }
      "the request excludes a data stream" in {
        List(getDataStreamAction, getDataStreamLifecycleAction).foreach { action =>
          val result = checkDataStreamRequestWithIndices(
            configured = NonEmptySet.of(indexNameVar("logs-*")),
            action = action,
            requestDataStreams = Set(dataStreamNamePattern("logs-*"), fullDataStreamName("-logs-db")),
            requestIndices = Set.empty,
            clusterDataStreams = statsClusterDataStreams
          )
          inside(result) { case Permitted(blockContext) =>
            blockContext.dataStreams should be(Set(fullDataStreamName("logs-app")))
          }
        }
      }
    }
  }

  private def modifyRequest(dataStream: NonEmptyString, index: NonEmptyString) =
    checkDataStreamRequestWithIndices(
      configured = NonEmptySet.of(indexNameVar("logs-*")),
      action = modifyDataStreamsAction,
      requestDataStreams = Set(DataStreamName.fromString(dataStream.value).get),
      requestIndices = Set(requestedIndex(index)),
      clusterDataStreams = statsClusterDataStreams
    )

  private def migrateRequest(alias: NonEmptyString) =
    checkDataStreamRequestWithIndices(
      configured = NonEmptySet.of(indexNameVar("logs-*")),
      action = Action("indices:admin/data_stream/migrate"),
      requestDataStreams = Set.empty,
      requestIndices = Set(requestedIndex(alias)),
      clusterDataStreams = statsClusterDataStreams
    )

  private lazy val modifyDataStreamsAction = Action("indices:admin/data_stream/modify")
  private lazy val getDataStreamAction = Action("indices:admin/data_stream/get")
  private lazy val getDataStreamLifecycleAction = Action("indices:admin/data_stream/lifecycle/get")

  private lazy val statsClusterDataStreams = Set(
    fullDataStreamName("logs-app"),
    fullDataStreamName("logs-db"),
    fullDataStreamName("audit-events")
  )

  private lazy val deleteDataStreamAction = Action("indices:admin/data_stream/delete")

  private lazy val dataStreamWriteActions = List(
    deleteDataStreamAction,
    Action("indices:admin/data_stream/create"),
    Action("indices:admin/data_stream/promote")
  )

  private def dataStreamNamePattern(value: NonEmptyString) = DataStreamName.Pattern.fromNes(value)

  private def testDataStreams = Set(
    fullLocalDataStreamWithAliases(fullDataStreamName("test1_ds")),
    fullLocalDataStreamWithAliases(fullDataStreamName("test2_ds")),
    fullLocalDataStreamWithAliases(fullDataStreamName("test3_ds")),
    fullLocalDataStreamWithAliases(fullDataStreamName("test4_ds")),
    fullLocalDataStreamWithAliases(fullDataStreamName("test5_ds"))
  )

}

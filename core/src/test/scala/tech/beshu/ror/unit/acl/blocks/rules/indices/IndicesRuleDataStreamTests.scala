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

import eu.timepit.refined.auto._
import cats.data.NonEmptySet
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.orders.indexOrder
import tech.beshu.ror.utils.TestsUtils.{clusterIndexName, fullDataStreamName, fullIndexName, fullLocalDataStreamWithAliases, fullLocalIndexWithAliases}
import tech.beshu.ror.utils.TestsUtils.unsafeNes

trait IndicesRuleDataStreamTests {
  this: BaseIndicesRuleTests =>

  "An IndicesRule for data streams" should {
    "match" when {
      "no data stream passed, one is configured, there is one real data stream" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test")),
          requestIndices = Set.empty,
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(fullLocalIndexWithAliases(fullIndexName(".ds-test"))),
            allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test")))
          ),
          found = Set(clusterIndexName("test")),
        )
      }
      "'_all' passed, one is configured, there is one real data stream" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test")),
          requestIndices = Set(clusterIndexName("_all")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(fullLocalIndexWithAliases(fullIndexName(".ds-test"))),
            allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test")))
          ),
          found = Set(clusterIndexName("test"))
        )
      }
      "'*' passed, one is configured, there is one real data stream" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test")),
          requestIndices = Set(clusterIndexName("*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(fullLocalIndexWithAliases(fullIndexName(".ds-test"))),
            allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test")))
          ),
          found = Set(clusterIndexName("test"))
        )
      }
      "one full name data stream passed, one full name data stream configured, no real data streams" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test")),
          requestIndices = Set(clusterIndexName("test")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          ),
          found = Set(clusterIndexName("test"))
        )
      }
      "one wildcard data stream passed, one full name data stream configured, no real data streams" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test")),
          requestIndices = Set(clusterIndexName("te*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test"))),
          ),
          found = Set(clusterIndexName("test"))
        )
      }
      "one full name data stream passed, one wildcard data stream configured, no real data streams" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("t*")),
          requestIndices = Set(clusterIndexName("test")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          ),
          found = Set(clusterIndexName("test"))
        )
      }
      "two full name data streams passed, the same two full name data streams configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1"), indexNameVar("test2")),
          requestIndices = Set(clusterIndexName("test2"), clusterIndexName("test1")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          ),
          found = Set(clusterIndexName("test2"), clusterIndexName("test1"))
        )
      }
      "two full name dat streams passed, one the same, one different data stream configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1"), indexNameVar("test2")),
          requestIndices = Set(clusterIndexName("test1"), clusterIndexName("test3")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          ),
          found = Set(clusterIndexName("test1"))
        )
      }
      "two matching wildcard data streams passed, two full name data streams configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1"), indexNameVar("test2")),
          requestIndices = Set(clusterIndexName("*2"), clusterIndexName("*1")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          ),
          found = Set(clusterIndexName("test1"), clusterIndexName("test2"))
        )
      }
      "two full name data streams passed, two matching wildcard data streams configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*1"), indexNameVar("*2")),
          requestIndices = Set(clusterIndexName("test2"), clusterIndexName("test1")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          ),
          found = Set(clusterIndexName("test2"), clusterIndexName("test1"))
        )
      }
      "two full name data streams passed, one matching full name and one non-matching wildcard data stream configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1"), indexNameVar("*2")),
          requestIndices = Set(clusterIndexName("test1"), clusterIndexName("test3")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          ),
          found = Set(clusterIndexName("test1"))
        )
      }
      "one matching wildcard data stream passed and one non-matching full name index, two full name data streams configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1"), indexNameVar("*2")),
          requestIndices = Set(clusterIndexName("*1"), clusterIndexName("test3")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          ),
          found = Set(clusterIndexName("test1"))
        )
      }
      "one full name alias passed, full name data stream related to that alias configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test-ds")),
          requestIndices = Set(clusterIndexName("test-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("test-ds"), Set(fullDataStreamName("test-alias")))
            )
          ),
          found = Set(clusterIndexName("test-ds"))
        )
      }
      "wildcard alias passed, full name data stream related to alias matching passed one configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test-ds")),
          requestIndices = Set(clusterIndexName("*-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("test-ds"), Set(fullDataStreamName("test-alias")))
            )
          ),
          found = Set(clusterIndexName("test-ds"))
        )
      }
      "one full name alias passed, wildcard data stream configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*-ds")),
          requestIndices = Set(clusterIndexName("test-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("test-ds"), Set(fullDataStreamName("test-alias")))
            )
          ),
          found = Set(clusterIndexName("test-ds"))
        )
      }
      "one alias passed, only subset of alias data streams configured" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test-ds1"), indexNameVar("test-ds2")),
          requestIndices = Set(clusterIndexName("test-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("test-ds1"), Set(fullDataStreamName("test-alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test-ds2"), Set(fullDataStreamName("test-alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test-ds3"), Set(fullDataStreamName("test-alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test-ds4"), Set(fullDataStreamName("test-alias")))
            )
          ),
          found = Set(clusterIndexName("test-ds1"), clusterIndexName("test-ds2"))
        )
      }
      "cross cluster data stream is used together with local data stream" in {
        assertMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("odd:test1*"), indexNameVar("local*")),
          requestIndices = Set(clusterIndexName("local_ds*"), clusterIndexName("odd:test1_ds*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("local_ds1")),
              fullLocalDataStreamWithAliases(fullDataStreamName("local_ds2")),
              fullLocalDataStreamWithAliases(fullDataStreamName("other"))
            ),
            allRemoteDataStreamsAndAliases = Task.now(Set(
              fullRemoteDataStreamWithAliases("etl1", "c01-logs-smg-stats-2020-03-27"),
              fullRemoteDataStreamWithAliases("etl1", "c01-logs-smg-stats-2020-03-28"),
              fullRemoteDataStreamWithAliases("etl1", "c01-logs-smg-stats-2020-03-29"),
              fullRemoteDataStreamWithAliases("odd", "test1_ds1"),
              fullRemoteDataStreamWithAliases("odd", "test1_ds2"),
              fullRemoteDataStreamWithAliases("odd", "test2_ds1"),
            ))
          ),
          found = Set(
            clusterIndexName("local_ds1"),
            clusterIndexName("local_ds2"),
            clusterIndexName("odd:test1_ds1"),
            clusterIndexName("odd:test1_ds2")
          )
        )
      }
      "multi filterable request tries to fetch data for allowed and not allowed data streams" in {
        assertMatchRuleForMultiIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1")),
          indexPacks = Indices.Found(Set(clusterIndexName("test1"), clusterIndexName("test2"))) :: Nil,
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          ),
          allowed = Indices.Found(Set(clusterIndexName("test1"))) :: Nil
        )
      }
    }
    "not match" when {
      "no data stream passed, one is configured, no real data streams" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test")),
          requestIndices = Set.empty,
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set.empty
          )
        )
      }
      "'_all' passed, one is configured, no real data streams" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test")),
          requestIndices = Set(clusterIndexName("_all")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set.empty
          )
        )
      }
      "'*' passed, one is configured, no real data streams" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test")),
          requestIndices = Set(clusterIndexName("*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set.empty
          )
        )
      }
      "one full name data stream passed, different one full name data stream configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1")),
          requestIndices = Set(clusterIndexName("test2")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          )
        )
      }
      "one wildcard data stream passed, non-matching data stream with full name configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1")),
          requestIndices = Set(clusterIndexName("*2")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          )
        )
      }
      "one full name data stream passed, non-matching data stream with wildcard configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*1")),
          requestIndices = Set(clusterIndexName("test2")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          )
        )
      }
      "two full name data streams passed, different two full name data streams configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1"), indexNameVar("test2")),
          requestIndices = Set(clusterIndexName("test4"), clusterIndexName("test3")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          )
        )
      }
      "two wildcard data streams passed, non-matching two full name data streams configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test1"), indexNameVar("test2")),
          requestIndices = Set(clusterIndexName("*4"), clusterIndexName("*3")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          )
        )
      }
      "two full name data streams passed, non-matching two wildcard data streams configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*1"), indexNameVar("*2")),
          requestIndices = Set(clusterIndexName("test4"), clusterIndexName("test3")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = testDataStreams
          )
        )
      }
      "one full name alias passed, full name data stream with no alias configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test-ds")),
          requestIndices = Set(clusterIndexName("test-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("test-ds")),
              fullLocalDataStreamWithAliases(fullDataStreamName("test-ds2"), Set(fullDataStreamName("test-alias")))
            )
          )
        )
      }
      "wildcard alias passed, full name data stream with no alias configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test-ds")),
          requestIndices = Set(clusterIndexName("*-alias")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("test-ds"), Set.empty),
              fullLocalDataStreamWithAliases(fullDataStreamName("test-ds2"), Set(fullDataStreamName("test-alias")))
            )
          )
        )
      }
      "full name data stream passed, data stream alias configured" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("test12-alias")),
          requestIndices = Set(clusterIndexName("test-ds1")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set.empty,
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("test-ds1"), Set(fullDataStreamName("test12-alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test-ds2"), Set(fullDataStreamName("test12-alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test-ds3"), Set(fullDataStreamName("test34-alias"))),
              fullLocalDataStreamWithAliases(fullDataStreamName("test-ds4"), Set(fullDataStreamName("test34-alias")))
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
            configured = NonEmptySet.of(indexNameVar("etl*:*-logs-smg-stats-*")),
            requestIndices = Set(clusterIndexName("e*:*-logs-smg-stats-*")),
            modifyRequestContext = _.copy(
              allIndicesAndAliases = Set.empty,
              allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test"), Set.empty)),
              allRemoteDataStreamsAndAliases = Task.now(Set(
                fullRemoteDataStreamWithAliases("etl1", "c01-logs-smg-stats-2020-03-27"),
                fullRemoteDataStreamWithAliases("etl1", "c01-logs-smg-stats-2020-03-28"),
                fullRemoteDataStreamWithAliases("etl1", "c01-logs-smg-stats-2020-03-29"),
                fullRemoteDataStreamWithAliases("etl1", "other-index"),
                fullRemoteDataStreamWithAliases("other", "c02-logs-smg-stats-2020-03-27"),
                fullRemoteDataStreamWithAliases("other", "c02-logs-smg-stats-2020-03-28"),
                fullRemoteDataStreamWithAliases("other", "c02-logs-smg-stats-2020-03-29")
              ))
            ),
            found = Set(
              clusterIndexName("etl1:c01-logs-smg-stats-2020-03-27"),
              clusterIndexName("etl1:c01-logs-smg-stats-2020-03-28"),
              clusterIndexName("etl1:c01-logs-smg-stats-2020-03-29")
            )
          )
        }
        "requested data stream name with wildcard is more general version of the configured data stream name with wildcard" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("etl*:*-logs-smg-stats-*")),
            requestIndices = Set(clusterIndexName("e*:*-logs-smg-*")),
            modifyRequestContext = _.copy(
              allIndicesAndAliases = Set.empty,
              allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test"), Set.empty)),
              allRemoteDataStreamsAndAliases = Task.now(Set(
                fullRemoteDataStreamWithAliases("etl1", "c01-logs-smg-stats-2020-03-27"),
                fullRemoteDataStreamWithAliases("etl1", "c01-logs-smg-stats-2020-03-28"),
                fullRemoteDataStreamWithAliases("etl1", "c01-logs-smg-stats-2020-03-29"),
                fullRemoteDataStreamWithAliases("etl1", "other-index"),
                fullRemoteDataStreamWithAliases("other", "c02-logs-smg-stats-2020-03-27"),
                fullRemoteDataStreamWithAliases("other", "c02-logs-smg-stats-2020-03-28"),
                fullRemoteDataStreamWithAliases("other", "c02-logs-smg-stats-2020-03-29")
              ))
            ),
            found = Set(
              clusterIndexName("etl1:c01-logs-smg-stats-2020-03-27"),
              clusterIndexName("etl1:c01-logs-smg-stats-2020-03-28"),
              clusterIndexName("etl1:c01-logs-smg-stats-2020-03-29")
            )
          )
        }
        "requested data stream name with wildcard is more specialized version of the configured data stream name with wildcard" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("etl*:*-logs-smg-stats-*")),
            requestIndices = Set(clusterIndexName("e*:*-logs-smg-stats-2020-03-2*")),
            modifyRequestContext = _.copy(
              allIndicesAndAliases = Set.empty,
              allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test"), Set.empty)),
              allRemoteDataStreamsAndAliases = Task.now(Set(
                fullRemoteDataStreamWithAliases("etl1", "c01-logs-smg-stats-2020-03-27"),
                fullRemoteDataStreamWithAliases("etl1", "c01-logs-smg-stats-2020-03-28"),
                fullRemoteDataStreamWithAliases("etl1", "c01-logs-smg-stats-2020-03-29"),
                fullRemoteDataStreamWithAliases("etl1", "c01-logs-smg-stats-2020-03-30"),
                fullRemoteDataStreamWithAliases("etl1", "other-index"),
                fullRemoteDataStreamWithAliases("other", "c02-logs-smg-stats-2020-03-27"),
                fullRemoteDataStreamWithAliases("other", "c02-logs-smg-stats-2020-03-28"),
                fullRemoteDataStreamWithAliases("other", "c02-logs-smg-stats-2020-03-29")
              ))
            ),
            found = Set(
              clusterIndexName("etl1:c01-logs-smg-stats-2020-03-27"),
              clusterIndexName("etl1:c01-logs-smg-stats-2020-03-28"),
              clusterIndexName("etl1:c01-logs-smg-stats-2020-03-29")
            )
          )
        }
        "requested data stream name with wildcard doesn't match the configured data stream name with wildcard but it does match the resolved data stream name" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("etl*:*-logs-smg-stats-*")),
            requestIndices = Set(clusterIndexName("e*:c0*")),
            modifyRequestContext = _.copy(
              allIndicesAndAliases = Set.empty,
              allDataStreamsAndAliases = Set(fullLocalDataStreamWithAliases(fullDataStreamName("test"), Set.empty)),
              allRemoteDataStreamsAndAliases = Task.now(Set(
                fullRemoteDataStreamWithAliases("etl1", "c01-logs-smg-stats-2020-03-27"),
                fullRemoteDataStreamWithAliases("etl1", "c01-logs-smg-stats-2020-03-28"),
                fullRemoteDataStreamWithAliases("etl1", "c01-logs-smg-stats-2020-03-29"),
                fullRemoteDataStreamWithAliases("etl1", "other-index"),
                fullRemoteDataStreamWithAliases("other", "c02-logs-smg-stats-2020-03-27"),
                fullRemoteDataStreamWithAliases("other", "c02-logs-smg-stats-2020-03-28"),
                fullRemoteDataStreamWithAliases("other", "c02-logs-smg-stats-2020-03-29")
              ))
            ),
            found = Set(
              clusterIndexName("etl1:c01-logs-smg-stats-2020-03-27"),
              clusterIndexName("etl1:c01-logs-smg-stats-2020-03-28"),
              clusterIndexName("etl1:c01-logs-smg-stats-2020-03-29")
            )
          )
        }
      }
    }
    "not match" when {
      "not allowed cluster data streams are being called" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*-logs-smg-stats-*"), indexNameVar("etl*:*-logs-smg-stats-*")),
          requestIndices = Set(clusterIndexName("pub*:*logs*")),
          modifyRequestContext = _.copy(
            allDataStreamsAndAliases = Set(
              fullLocalDataStreamWithAliases(fullDataStreamName("clocal-logs-smg-stats-2020-03-27")),
              fullLocalDataStreamWithAliases(fullDataStreamName("clocal-logs-smg-stats-2020-03-28")),
              fullLocalDataStreamWithAliases(fullDataStreamName("clocal-logs-smg-stats-2020-03-29")),
            ),
            allRemoteDataStreamsAndAliases = Task.now(Set(
              fullRemoteDataStreamWithAliases("etl1", "c01-logs-smg-stats-2020-03-27"),
              fullRemoteDataStreamWithAliases("etl1", "c01-logs-smg-stats-2020-03-28"),
              fullRemoteDataStreamWithAliases("etl1", "c01-logs-smg-stats-2020-03-29"),
              fullRemoteDataStreamWithAliases("etl1", "other-index"),
              fullRemoteDataStreamWithAliases("other", "c02-logs-smg-stats-2020-03-27"),
              fullRemoteDataStreamWithAliases("other", "c02-logs-smg-stats-2020-03-28"),
              fullRemoteDataStreamWithAliases("other", "c02-logs-smg-stats-2020-03-29")
            ))
          )
        )
      }
    }
  }

  private def testDataStreams = Set(
    fullLocalDataStreamWithAliases(fullDataStreamName("test1")),
    fullLocalDataStreamWithAliases(fullDataStreamName("test2")),
    fullLocalDataStreamWithAliases(fullDataStreamName("test3")),
    fullLocalDataStreamWithAliases(fullDataStreamName("test4")),
    fullLocalDataStreamWithAliases(fullDataStreamName("test5"))
  )
}

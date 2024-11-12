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
import tech.beshu.ror.accesscontrol.orders.custerIndexNameOrder
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*

trait IndicesRuleRemoteIndexTests {
  this: BaseIndicesRuleTests =>

  "An IndicesRule for request with remote indices" should {
    "match" when {
      "remote indices are used" when {
        "requested index name with wildcard is the same as configured index name with wildcard" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("etl*:*-logs-smg-stats-*")),
            requestIndices = Set(requestedIndex("e*:*-logs-smg-stats-*")),
            modifyRequestContext = _.copy(
              allIndicesAndAliases = Set(fullLocalIndexWithAliases(fullIndexName("test"), Set.empty)),
              allRemoteIndicesAndAliases = Task.now(Set(
                fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-27"),
                fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-28"),
                fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-29"),
                fullRemoteIndexWithAliases("etl1", "other-index"),
                fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-27"),
                fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-28"),
                fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-29")
              ))
            ),
            filteredRequestedIndices = Set(
              requestedIndex("etl1:c01-logs-smg-stats-2020-03-27"),
              requestedIndex("etl1:c01-logs-smg-stats-2020-03-28"),
              requestedIndex("etl1:c01-logs-smg-stats-2020-03-29")
            )
          )
        }
        "requested index name with wildcard is more general version of the configured index name with wildcard" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("etl*:*-logs-smg-stats-*")),
            requestIndices = Set(requestedIndex("e*:*-logs-smg-*")),
            modifyRequestContext = _.copy(
              allIndicesAndAliases = Set(fullLocalIndexWithAliases(fullIndexName("test"), Set.empty)),
              allRemoteIndicesAndAliases = Task.now(Set(
                fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-27"),
                fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-28"),
                fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-29"),
                fullRemoteIndexWithAliases("etl1", "other-index"),
                fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-27"),
                fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-28"),
                fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-29")
              ))
            ),
            filteredRequestedIndices = Set(
              requestedIndex("etl1:c01-logs-smg-stats-2020-03-27"),
              requestedIndex("etl1:c01-logs-smg-stats-2020-03-28"),
              requestedIndex("etl1:c01-logs-smg-stats-2020-03-29")
            )
          )
        }
        "requested index name with wildcard is more specialized version of the configured index name with wildcard" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("etl*:*-logs-smg-stats-*")),
            requestIndices = Set(requestedIndex("e*:*-logs-smg-stats-2020-03-2*")),
            modifyRequestContext = _.copy(
              allIndicesAndAliases = Set(fullLocalIndexWithAliases(fullIndexName("test"), Set.empty)),
              allRemoteIndicesAndAliases = Task.now(Set(
                fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-27"),
                fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-28"),
                fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-29"),
                fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-30"),
                fullRemoteIndexWithAliases("etl1", "other-index"),
                fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-27"),
                fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-28"),
                fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-29")
              ))
            ),
            filteredRequestedIndices = Set(
              requestedIndex("etl1:c01-logs-smg-stats-2020-03-27"),
              requestedIndex("etl1:c01-logs-smg-stats-2020-03-28"),
              requestedIndex("etl1:c01-logs-smg-stats-2020-03-29")
            )
          )
        }
        "requested index name with wildcard doesn't match the configured index name with wildcard but it does match the resolved index name" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("etl*:*-logs-smg-stats-*")),
            requestIndices = Set(requestedIndex("e*:c0*")),
            modifyRequestContext = _.copy(
              allIndicesAndAliases = Set(fullLocalIndexWithAliases(fullIndexName("test"), Set.empty)),
              allRemoteIndicesAndAliases = Task.now(Set(
                fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-27"),
                fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-28"),
                fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-29"),
                fullRemoteIndexWithAliases("etl1", "other-index"),
                fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-27"),
                fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-28"),
                fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-29")
              ))
            ),
            filteredRequestedIndices = Set(
              requestedIndex("etl1:c01-logs-smg-stats-2020-03-27"),
              requestedIndex("etl1:c01-logs-smg-stats-2020-03-28"),
              requestedIndex("etl1:c01-logs-smg-stats-2020-03-29")
            )
          )
        }
      }
    }
    "not match" when {
      "not allowed cluster indices are being called" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*-logs-smg-stats-*"), indexNameVar("etl*:*-logs-smg-stats-*")),
          requestIndices = Set(requestedIndex("pub*:*logs*")),
          modifyRequestContext = _.copy(
            allIndicesAndAliases = Set(
              fullLocalIndexWithAliases(fullIndexName("clocal-logs-smg-stats-2020-03-27")),
              fullLocalIndexWithAliases(fullIndexName("clocal-logs-smg-stats-2020-03-28")),
              fullLocalIndexWithAliases(fullIndexName("clocal-logs-smg-stats-2020-03-29")),
            ),
            allRemoteIndicesAndAliases = Task.now(Set(
              fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-27"),
              fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-28"),
              fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-29"),
              fullRemoteIndexWithAliases("etl1", "other-index"),
              fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-27"),
              fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-28"),
              fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-29")
            ))
          )
        )
      }
    }
  }
}

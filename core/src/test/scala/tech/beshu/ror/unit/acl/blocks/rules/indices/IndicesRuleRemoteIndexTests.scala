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
import tech.beshu.ror.accesscontrol.orders.custerIndexNameOrder
import tech.beshu.ror.mocks.MockEsServices
import tech.beshu.ror.mocks.MockEsServices.MockEsClusterService
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
            esServices = Some(
              MockEsServices.`with`(
                MockEsClusterService(
                  allIndicesAndAliases = Set(fullLocalIndexWithAliases(fullIndexName("test"), Set.empty)),
                  allRemoteIndicesAndAliases = Set(
                    fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-27"),
                    fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-28"),
                    fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-29"),
                    fullRemoteIndexWithAliases("etl1", "other-index"),
                    fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-27"),
                    fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-28"),
                    fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-29")
                  ),
                  allRemoteClusterNames = Set(clusterName("etl1"), clusterName("other"))
                )
              )
            ),
            filteredRequestedIndices = Set(
              requestedIndex("etl1:c01-logs-smg-stats-2020-03-27"),
              requestedIndex("etl1:c01-logs-smg-stats-2020-03-28"),
              requestedIndex("etl1:c01-logs-smg-stats-2020-03-29")
            ),
            allAllowedClusters = Set(clusterName("etl1"))
          )
        }
        "requested index name with wildcard is more general version of the configured index name with wildcard" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("etl*:*-logs-smg-stats-*")),
            requestIndices = Set(requestedIndex("e*:*-logs-smg-*")),
            esServices = Some(
              MockEsServices.`with`(
                MockEsClusterService(
                  allIndicesAndAliases = Set(fullLocalIndexWithAliases(fullIndexName("test"), Set.empty)),
                  allRemoteIndicesAndAliases = Set(
                    fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-27"),
                    fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-28"),
                    fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-29"),
                    fullRemoteIndexWithAliases("etl1", "other-index"),
                    fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-27"),
                    fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-28"),
                    fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-29")
                  ),
                  allRemoteClusterNames = Set(clusterName("etl1"), clusterName("other"))
                )
              )
            ),
            filteredRequestedIndices = Set(
              requestedIndex("etl1:c01-logs-smg-stats-2020-03-27"),
              requestedIndex("etl1:c01-logs-smg-stats-2020-03-28"),
              requestedIndex("etl1:c01-logs-smg-stats-2020-03-29")
            ),
            allAllowedClusters = Set(clusterName("etl1"))
          )
        }
        "requested index name with wildcard is more specialized version of the configured index name with wildcard" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("etl*:*-logs-smg-stats-*")),
            requestIndices = Set(requestedIndex("e*:*-logs-smg-stats-2020-03-2*")),
            esServices = Some(
              MockEsServices.`with`(
                MockEsClusterService(
                  allIndicesAndAliases = Set(fullLocalIndexWithAliases(fullIndexName("test"), Set.empty)),
                  allRemoteIndicesAndAliases = Set(
                    fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-27"),
                    fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-28"),
                    fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-29"),
                    fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-30"),
                    fullRemoteIndexWithAliases("etl1", "other-index"),
                    fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-27"),
                    fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-28"),
                    fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-29")
                  ),
                  allRemoteClusterNames = Set(clusterName("etl1"), clusterName("other"))
                )
              )
            ),
            filteredRequestedIndices = Set(
              requestedIndex("etl1:c01-logs-smg-stats-2020-03-27"),
              requestedIndex("etl1:c01-logs-smg-stats-2020-03-28"),
              requestedIndex("etl1:c01-logs-smg-stats-2020-03-29")
            ),
            allAllowedClusters = Set(clusterName("etl1"))
          )
        }
        "requested index name with wildcard doesn't match the configured index name with wildcard but it does match the resolved index name" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("etl*:*-logs-smg-stats-*")),
            requestIndices = Set(requestedIndex("e*:c0*")),
            esServices = Some(
              MockEsServices.`with`(
                MockEsClusterService(
                  allIndicesAndAliases = Set(fullLocalIndexWithAliases(fullIndexName("test"), Set.empty)),
                  allRemoteIndicesAndAliases = Set(
                    fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-27"),
                    fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-28"),
                    fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-29"),
                    fullRemoteIndexWithAliases("etl1", "other-index"),
                    fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-27"),
                    fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-28"),
                    fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-29")
                  ),
                  allRemoteClusterNames = Set(clusterName("etl1"), clusterName("other"))
                )
              )
            ),
            filteredRequestedIndices = Set(
              requestedIndex("etl1:c01-logs-smg-stats-2020-03-27"),
              requestedIndex("etl1:c01-logs-smg-stats-2020-03-28"),
              requestedIndex("etl1:c01-logs-smg-stats-2020-03-29")
            ),
            allAllowedClusters = Set(clusterName("etl1"))
          )
        }
        "full name remote index passed, same full name remote index configured, no real indices" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("etl1:my-index")),
            requestIndices = Set(requestedIndex("etl1:my-index")),
            filteredRequestedIndices = Set(requestedIndex("etl1:my-index")),
            allAllowedClusters = Set(clusterName("etl1"))
          )
        }
        "remote alias passed, remote index behind that alias configured" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("etl1:my-index")),
            requestIndices = Set(requestedIndex("etl1:my-alias")),
            esServices = Some(
              MockEsServices.`with`(
                MockEsClusterService(
                  allRemoteIndicesAndAliases = Set(
                    fullRemoteIndexWithAliases("etl1", "my-index", "my-alias")
                  ),
                  allRemoteClusterNames = Set(clusterName("etl1"))
                )
              )
            ),
            filteredRequestedIndices = Set(requestedIndex("etl1:my-index")),
            allAllowedClusters = Set(clusterName("etl1"))
          )
        }
        "remote alias passed, remote alias configured" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("etl1:my-alias")),
            requestIndices = Set(requestedIndex("etl1:my-alias")),
            esServices = Some(
              MockEsServices.`with`(
                MockEsClusterService(
                  allRemoteIndicesAndAliases = Set(
                    fullRemoteIndexWithAliases("etl1", "my-index", "my-alias")
                  ),
                  allRemoteClusterNames = Set(clusterName("etl1"))
                )
              )
            ),
            filteredRequestedIndices = Set(requestedIndex("etl1:my-alias")),
            allAllowedClusters = Set(clusterName("etl1"))
          )
        }
        "wildcard cluster and full name index passed, matching full name cluster and full name index configured" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("etl1:my-index")),
            requestIndices = Set(requestedIndex("etl*:my-index")),
            esServices = Some(
              MockEsServices.`with`(
                MockEsClusterService(
                  allRemoteIndicesAndAliases = Set(
                    fullRemoteIndexWithAliases("etl1", "my-index"),
                    fullRemoteIndexWithAliases("etl2", "my-index"),
                    fullRemoteIndexWithAliases("other", "my-index"),
                  ),
                  allRemoteClusterNames = Set(clusterName("etl1"), clusterName("etl2"), clusterName("other"))
                )
              )
            ),
            filteredRequestedIndices = Set(requestedIndex("etl1:my-index")),
            allAllowedClusters = Set(clusterName("etl1"))
          )
        }
      }
    }
    "not match" when {
      "not allowed cluster indices are being called" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("*-logs-smg-stats-*"), indexNameVar("etl*:*-logs-smg-stats-*")),
          requestIndices = Set(requestedIndex("pub*:*logs*")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set(
                  fullLocalIndexWithAliases(fullIndexName("clocal-logs-smg-stats-2020-03-27")),
                  fullLocalIndexWithAliases(fullIndexName("clocal-logs-smg-stats-2020-03-28")),
                  fullLocalIndexWithAliases(fullIndexName("clocal-logs-smg-stats-2020-03-29")),
                ),
                allRemoteIndicesAndAliases = Set(
                  fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-27"),
                  fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-28"),
                  fullRemoteIndexWithAliases("etl1", "c01-logs-smg-stats-2020-03-29"),
                  fullRemoteIndexWithAliases("etl1", "other-index"),
                  fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-27"),
                  fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-28"),
                  fullRemoteIndexWithAliases("other", "c02-logs-smg-stats-2020-03-29")
                ),
                allRemoteClusterNames = Set(clusterName("etl1"), clusterName("other"))
              )
            )
          ),
          allAllowedClusters = Set(clusterName("etl1"), clusterName("(local)"))
        )
      }
      "remote cluster matches configured pattern but requested index is not allowed" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("etl1:allowed-*")),
          requestIndices = Set(requestedIndex("etl1:other-index")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allRemoteIndicesAndAliases = Set(
                  fullRemoteIndexWithAliases("etl1", "other-index"),
                  fullRemoteIndexWithAliases("etl1", "allowed-index"),
                ),
                allRemoteClusterNames = Set(clusterName("etl1"))
              )
            )
          ),
          allAllowedClusters = Set(clusterName("etl1"))
        )
      }
      "requested cluster does not match configured cluster pattern" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("etl1:my-index")),
          requestIndices = Set(requestedIndex("other:my-index")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allRemoteIndicesAndAliases = Set(
                  fullRemoteIndexWithAliases("other", "my-index"),
                ),
                allRemoteClusterNames = Set(clusterName("other"))
              )
            )
          ),
          allAllowedClusters = Set.empty
        )
      }
    }
  }

}

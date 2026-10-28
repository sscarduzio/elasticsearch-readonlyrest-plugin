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
import monix.execution.Scheduler.Implicits.global
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{
  FilterableMultiRequestBlockContext,
  GeneralIndexRequestBlockContext
}
import tech.beshu.ror.accesscontrol.blocks.Decision.Permitted
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Remote.ClusterName
import tech.beshu.ror.accesscontrol.domain.{ClusterIndexName, IndexName, RequestedIndex}
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
        "a remote cluster that the request does not name fails" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("*:logs-*")),
            requestIndices = Set(requestedIndex("prod:*")),
            esServices = Some(
              MockEsServices.`with`(
                MockEsClusterService(
                  allRemoteIndicesAndAliases = Set(fullRemoteIndexWithAliases("prod", "logs-1")),
                  failingClusters = Map(clusterName("netflow") -> netflowError)
                )
              )
            ),
            filteredRequestedIndices = Set(requestedIndex("prod:logs-1")),
            allAllowedClusters = Set(clusterName("prod"))
          )
        }
        "a remote cluster that no allowed pattern names fails" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("prod:logs-*")),
            requestIndices = Set(requestedIndex("*:logs-*")),
            esServices = Some(prodAvailableAndNetflowUnavailable),
            filteredRequestedIndices = Set(requestedIndex("prod:logs-1")),
            allAllowedClusters = Set(clusterName("prod"))
          )
        }
        "the request names a cluster pattern with \"*\" and the settings name the full cluster" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("prod1:logs*")),
            requestIndices = Set(requestedIndex("prod*:logs*")),
            esServices = Some(
              MockEsServices.`with`(
                MockEsClusterService(allRemoteIndicesAndAliases = Set(fullRemoteIndexWithAliases("prod1", "logs-1")))
              )
            ),
            filteredRequestedIndices = Set(requestedIndex("prod1:logs-1")),
            allAllowedClusters = Set(clusterName("prod1"))
          )
        }
        "a remote cluster that the request names only in an exclusion fails" in {
          assertMatchRuleForIndexRequest(
            configured = NonEmptySet.of(indexNameVar("*:logs-*")),
            requestIndices = Set(requestedIndex("prod:*"), requestedIndex("-netflow:*")),
            esServices = Some(
              MockEsServices.`with`(
                MockEsClusterService(
                  allRemoteIndicesAndAliases = Set(fullRemoteIndexWithAliases("prod", "logs-1")),
                  failingClusters = Map(clusterName("netflow") -> netflowError)
                )
              )
            ),
            filteredRequestedIndices = Set(requestedIndex("prod:logs-1")),
            allAllowedClusters = Set(clusterName("prod"))
          )
        }
      }
    }
    "match with only a nonexistent index on the unavailable remote cluster, so that ES fails the search" when {
      "the request names the unavailable cluster with a cluster pattern" in {
        val result = checkIndexRequest(
          configuredValues = NonEmptySet.of(indexNameVar("*:logs-*")),
          requestIndices = Set(requestedIndex("*:*")),
          esServices = Some(prodAvailableAndNetflowUnavailable)
        ).runSyncUnsafe()
        inside(result) { case Permitted(blockContext: GeneralIndexRequestBlockContext) =>
          assertNonexistentIndexOn(clusterName("netflow"), blockContext.filteredIndices)
        }
      }
      "the request names local indices and the unavailable cluster" in {
        val result = checkIndexRequest(
          configuredValues = NonEmptySet.of(indexNameVar("test*"), indexNameVar("*:logs-*")),
          requestIndices = Set(requestedIndex("test*"), requestedIndex("netflow:logs-*")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(
                allIndicesAndAliases = Set(fullLocalIndexWithAliases(fullIndexName("test1"))),
                failingClusters = Map(clusterName("netflow") -> netflowError)
              )
            )
          )
        ).runSyncUnsafe()
        inside(result) { case Permitted(blockContext: GeneralIndexRequestBlockContext) =>
          assertNonexistentIndexOn(clusterName("netflow"), blockContext.filteredIndices)
        }
      }
    }
    "not match" when {
      "the request names only an unavailable cluster that no allowed pattern names" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("prod:logs-*")),
          requestIndices = Set(requestedIndex("netflow:logs-*")),
          esServices = Some(prodAvailableAndNetflowUnavailable),
          allAllowedClusters = Set(clusterName("prod"))
        )
      }
      "the request names a cluster with \"?\", because ES does not read \"?\" as a wildcard in the cluster name" in {
        assertNotMatchRuleForIndexRequest(
          configured = NonEmptySet.of(indexNameVar("prod1:logs*")),
          requestIndices = Set(requestedIndex("prod?:logs*")),
          esServices = Some(
            MockEsServices.`with`(
              MockEsClusterService(allRemoteIndicesAndAliases = Set(fullRemoteIndexWithAliases("prod1", "logs-1")))
            )
          ),
          allAllowedClusters = Set(clusterName("prod1"))
        )
      }
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

  "An IndicesRule for a multi-request with remote indices" should {
    "fail only the item that names an unavailable remote cluster" when {
      "the item names only the unavailable cluster" in {
        val result = checkMultiIndexRequest(
          configuredValues = NonEmptySet.of(indexNameVar("*:logs-*")),
          indexPacks = List(
            Indices.Found(Set(requestedIndex("prod:logs-*"))),
            Indices.Found(Set(requestedIndex("netflow:logs-*")))
          ),
          esServices = Some(prodAvailableAndNetflowUnavailable)
        ).runSyncUnsafe()
        inside(result) { case Permitted(blockContext: FilterableMultiRequestBlockContext) =>
          inside(blockContext.indexPacks) { case prodPack :: netflowPack :: Nil =>
            prodPack should be(Indices.Found(Set(requestedIndex("prod:logs-1"))))
            assertNonexistentIndexOn(clusterName("netflow"), netflowPack)
          }
        }
      }
      "the item names an available cluster and the unavailable cluster" in {
        val result = checkMultiIndexRequest(
          configuredValues = NonEmptySet.of(indexNameVar("*:logs-*")),
          indexPacks = List(
            Indices.Found(Set(requestedIndex("prod:logs-*"))),
            Indices.Found(Set(requestedIndex("prod:logs-*"), requestedIndex("netflow:logs-*")))
          ),
          esServices = Some(prodAvailableAndNetflowUnavailable)
        ).runSyncUnsafe()
        inside(result) { case Permitted(blockContext: FilterableMultiRequestBlockContext) =>
          inside(blockContext.indexPacks) { case prodPack :: mixedPack :: Nil =>
            prodPack should be(Indices.Found(Set(requestedIndex("prod:logs-1"))))
            assertNonexistentIndexOn(clusterName("netflow"), mixedPack)
          }
        }
      }
      "the only item names the unavailable cluster" in {
        val result = checkMultiIndexRequest(
          configuredValues = NonEmptySet.of(indexNameVar("*:logs-*")),
          indexPacks = List(Indices.Found(Set(requestedIndex("netflow:logs-*")))),
          esServices = Some(prodAvailableAndNetflowUnavailable)
        ).runSyncUnsafe()
        inside(result) { case Permitted(blockContext: FilterableMultiRequestBlockContext) =>
          inside(blockContext.indexPacks) { case netflowPack :: Nil =>
            assertNonexistentIndexOn(clusterName("netflow"), netflowPack)
          }
        }
      }
    }
  }

  "The ES wildcard pattern of a remote cluster name pattern" should {
    "read \"?\" as a plain character" in {
      val pattern = ClusterName.Pattern.fromString("prod?*").get.esWildcardPattern
      pattern.matches("prod1") should be(false)
      pattern.matches("prod?1") should be(true)
    }
  }

  private lazy val netflowError = new IllegalStateException("Remote cluster [netflow] is unavailable")

  private lazy val prodAvailableAndNetflowUnavailable = MockEsServices.`with`(
    MockEsClusterService(
      allRemoteIndicesAndAliases = Set(fullRemoteIndexWithAliases("prod", "logs-1")),
      failingClusters = Map(clusterName("netflow") -> netflowError)
    )
  )

  private def assertNonexistentIndexOn(cluster: ClusterName.Full, pack: Indices): Unit =
    inside(pack) { case Indices.Found(indices) =>
      assertNonexistentIndexOn(cluster, indices)
    }

  private def assertNonexistentIndexOn(
      cluster: ClusterName.Full,
      indices: Set[RequestedIndex[ClusterIndexName]]
  ): Unit =
    inside(indices.toList) {
      case RequestedIndex(ClusterIndexName.Remote(IndexName.Full(name), indexCluster), false) :: Nil =>
        indexCluster should be(cluster)
        name.value should startWith("ROR_")
    }

}

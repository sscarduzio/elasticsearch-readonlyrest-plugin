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
package tech.beshu.ror.unit.es.services

import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Local as LocalIndexName
import tech.beshu.ror.accesscontrol.domain.DataStreamName.{Full, FullLocalDataStreamWithAliases}
import tech.beshu.ror.accesscontrol.domain.IndexAttribute.{Closed, Opened}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.es.services.EsClusterService.{LocalDataStreamsSnapshot, LocalIndicesSnapshot}
import tech.beshu.ror.syntax.*

class EsClusterServiceSnapshotsTest extends AnyWordSpec {

  "LocalIndicesSnapshot" should {
    "reuse precomputed sets and alias maps for attribute filters" in {
      val snapshot = new LocalIndicesSnapshot(Set(
        fullLocalIndex("open-1", Opened, "alias-open", "alias-both"),
        fullLocalIndex("closed-1", Closed, "alias-closed", "alias-both")
      ))

      snapshot.indicesAndAliasesFor(Set.empty) shouldBe Set(
        localIndex("open-1"),
        localIndex("closed-1"),
        localIndex("alias-open"),
        localIndex("alias-closed"),
        localIndex("alias-both")
      )
      snapshot.indicesAndAliasesFor(Set(Opened)) shouldBe Set(
        localIndex("open-1"),
        localIndex("alias-open"),
        localIndex("alias-both")
      )
      snapshot.indicesAndAliasesFor(Set(Closed)) shouldBe Set(
        localIndex("closed-1"),
        localIndex("alias-closed"),
        localIndex("alias-both")
      )

      snapshot.indicesPerAliasMapFor(Set.empty) shouldBe Map(
        localIndex("alias-open") -> Set(localIndex("open-1")),
        localIndex("alias-closed") -> Set(localIndex("closed-1")),
        localIndex("alias-both") -> Set(localIndex("open-1"), localIndex("closed-1"))
      )
      snapshot.indicesPerAliasMapFor(Set(Opened)) shouldBe Map(
        localIndex("alias-open") -> Set(localIndex("open-1")),
        localIndex("alias-both") -> Set(localIndex("open-1"))
      )
      snapshot.indicesPerAliasMapFor(Set(Closed)) shouldBe Map(
        localIndex("alias-closed") -> Set(localIndex("closed-1")),
        localIndex("alias-both") -> Set(localIndex("closed-1"))
      )
    }
  }

  "LocalDataStreamsSnapshot" should {
    "treat closed-only filters as empty and reuse opened data" in {
      val snapshot = new LocalDataStreamsSnapshot(Set(
        FullLocalDataStreamWithAliases(
          dataStreamName = dataStreamName("logs-app"),
          aliasesNames = Set(dataStreamName("logs-alias")),
          backingIndices = Set(indexName(".ds-logs-app-000001"), indexName(".ds-logs-app-000002"))
        )
      ))

      snapshot.dataStreamsAndAliasesFor(Set.empty) shouldBe Set(
        localIndex("logs-app"),
        localIndex("logs-alias"),
        localIndex(".ds-logs-app-000001"),
        localIndex(".ds-logs-app-000002")
      )
      snapshot.dataStreamsAndAliasesFor(Set(Closed)) shouldBe Set.empty[LocalIndexName]

      snapshot.dataStreamsPerAliasMapFor(Set.empty) shouldBe Map(
        localIndex("logs-alias") -> Set(localIndex("logs-app"))
      )
      snapshot.backingIndicesPerDataStreamMapFor(Set.empty) shouldBe Map(
        localIndex("logs-app") -> Set(localIndex(".ds-logs-app-000001"), localIndex(".ds-logs-app-000002"))
      )
      snapshot.dataStreamsPerAliasMapFor(Set(Closed)) shouldBe Map.empty[LocalIndexName, Set[LocalIndexName]]
      snapshot.backingIndicesPerDataStreamMapFor(Set(Closed)) shouldBe Map.empty[LocalIndexName, Set[LocalIndexName]]
    }
  }

  private def fullLocalIndex(name: String,
                             attribute: IndexAttribute,
                             aliases: String*): FullLocalIndexWithAliases =
    new FullLocalIndexWithAliases(indexName(name), attribute, aliases.map(indexName).toCovariantSet)

  private def localIndex(name: String): LocalIndexName =
    ClusterIndexName.Local(indexName(name))

  private def indexName(name: String): IndexName.Full =
    IndexName.Full.fromString(name).get

  private def dataStreamName(name: String): Full =
    DataStreamName.Full.fromString(name).get
}

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
package tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.clusterindices

import monix.execution.Scheduler.Implicits.global
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Local as LocalIndexName
import tech.beshu.ror.accesscontrol.domain.DataStreamName.FullLocalDataStreamWithAliases
import tech.beshu.ror.accesscontrol.domain.IndexAttribute.{Closed, Opened}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.mocks.{MockEsServices, MockRequestContext}
import tech.beshu.ror.syntax.*

class LocalIndicesManagerTest extends AnyWordSpec {

  "LocalIndicesManager" should {
    "filter alias sets using request index attributes" in {
      val requestContext = MockRequestContext.indices
        .copy(indexAttributes = Set(Closed))
        .withEsServices(MockEsServices.`with`(MockEsServices.MockEsClusterService(
          allIndicesAndAliases = Set(
            fullLocalIndex("open-1", Opened, "alias-open", "alias-both"),
            fullLocalIndex("closed-1", Closed, "alias-closed", "alias-both")
          ),
          allDataStreamsAndAliases = Set(
            FullLocalDataStreamWithAliases(
              dataStreamName = dataStreamName("logs-app"),
              aliasesNames = Set(dataStreamName("logs-alias")),
              backingIndices = Set(indexName(".ds-logs-app-000001"))
            )
          )
        )))
      given RequestId = requestContext.id.toRequestId
      val manager = new LocalIndicesManager(requestContext, PatternsMatcher.create(Set(localIndex("closed-1"))))

      manager.allAliases.runSyncUnsafe() shouldBe Set(
        localIndex("alias-closed"),
        localIndex("alias-both")
      )
      manager.allDataStreamAliases.runSyncUnsafe() shouldBe Set.empty[LocalIndexName]
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

  private def dataStreamName(name: String): DataStreamName.Full =
    DataStreamName.Full.fromString(name).get
}

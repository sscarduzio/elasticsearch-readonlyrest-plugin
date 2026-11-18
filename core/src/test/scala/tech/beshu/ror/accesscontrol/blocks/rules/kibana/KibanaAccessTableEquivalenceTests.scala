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
package tech.beshu.ror.accesscontrol.blocks.rules.kibana

import eu.timepit.refined.types.string.NonEmptyString
import org.apache.logging.log4j.core.config.Configurator
import org.apache.logging.log4j.{Level, LogManager}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers.*
import org.scalatest.wordspec.AnyWordSpec
import tech.beshu.ror.accesscontrol.blocks.BlockContext.DataStreamRequestBlockContext.BackingIndices
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{
  DataStreamRequestBlockContext,
  GeneralIndexRequestBlockContext,
  GeneralNonIndexRequestBlockContext
}
import tech.beshu.ror.accesscontrol.blocks.metadata.BlockMetadata
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaActionMatchers.*
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.mocks.{MockRequestContext, MockRestRequest}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.UniqueList

// Temporary: compares the access table with the decision of `BaseKibanaRule` before the refactor
// (`LegacyKibanaDecision`) for a matrix of requests. Remove both when the review of the refactor is done.
class KibanaAccessTableEquivalenceTests extends AnyWordSpec with BlockContextAssertion with BeforeAndAfterAll {

  private val kibanaRulesPackage = "tech.beshu.ror.accesscontrol.blocks.rules.kibana"
  private val logLevelBefore = LogManager.getLogger(kibanaRulesPackage).getLevel

  // The decision before the refactor writes about ten debug lines for each request
  override def beforeAll(): Unit = Configurator.setLevel(kibanaRulesPackage, Level.INFO)

  override def afterAll(): Unit = Configurator.setLevel(kibanaRulesPackage, logLevelBefore)

  "The kibana access table" should {
    "decide like the kibana rule before the refactor" in {
      val block = mock[Block]
      val decisions = for {
        restRequest <- restRequests
        action <- actions
        blockContext <- blockContexts(block, action, restRequest)
        kibanaIndex <- kibanaIndices
        access <- accessLevels
      } yield {
        val before = legacyDecisions(access).shouldMatch((blockContext, kibanaIndex))
        val after = KibanaAccessTable.isAllowed(access, new KibanaRequest(blockContext, kibanaIndex, rorIndex))
        Option.when(before != after) {
          s"$access, ${action.value}, kibana index ${kibanaIndex.stringify}, ${describe(blockContext)}: " +
            s"before $before, after $after"
        }
      }
      val differences = decisions.flatten
      decisions.size should be > 100000
      withClue(differences.take(30).mkString("\n", "\n", "\n")) {
        differences.size should be(0)
      }
    }
  }

  private lazy val accessLevels: List[KibanaAccess] = List(
    KibanaAccess.ROStrict,
    KibanaAccess.RO,
    KibanaAccess.ApiOnly,
    KibanaAccess.RW,
    KibanaAccess.Admin,
    KibanaAccess.Unrestricted
  )

  private lazy val rorIndex = RorSettingsIndex(fullIndexName(".readonlyrest"))

  private lazy val legacyDecisions: Map[KibanaAccess, LegacyKibanaDecision] =
    accessLevels
      .map(access => access -> new LegacyKibanaDecision(new LegacyKibanaDecision.Settings(access, rorIndex)))
      .toMap

  private lazy val kibanaIndices = List(kibanaIndexName(".kibana"), kibanaIndexName(".custom_kibana"))

  private lazy val actions: List[Action] = {
    val patterns = List(
      roActionPatternsMatcher,
      rwActionPatternsMatcher,
      clusterActionPatternsMatcher,
      adminActionPatternsMatcher,
      nonStrictActions,
      indicesWriteAction
    ).flatMap(_.patterns.toList.map(_.toString))
    val others = List(
      "xyz",
      "indices:admin/delete",
      "indices:admin/close",
      "indices:data/write/bulk",
      "indices:data/read/search",
      "cluster:internal_ror/config/manage",
      "cluster:admin/xpack/security/api_key/create",
      "cluster:admin/xpack/license/delete",
      "cluster:admin/settings/update",
      "indices:admin/data_stream/create",
      "indices:admin/data_stream/delete",
      "indices:monitor/data_stream/stats"
    )
    (patterns ++ patterns.map(_.replace("*", "x")) ++ others).distinct.map(Action.apply)
  }

  private lazy val indexSets: List[List[String]] = List(
    List(".kibana"),
    List(".kibana_8.19.0"),
    List(".kibana-reporting-.kibana"),
    List(".custom_kibana"),
    List("logs-1"),
    List(".kibana", "logs-1"),
    List("logs-1", ".kibana"),
    List("kibana_sample_data_logs"),
    List("kibana_sample_data_logs", "logs-1"),
    List(".readonlyrest"),
    List(".readonlyrest", "logs-1"),
    List("logs-1", ".readonlyrest"),
    List(".kibana-devnull"),
    List(".kibana-devnull", "logs-1"),
    List("logs-1", ".kibana-devnull"),
    List("*"),
    List("_all"),
    List(".kibana*")
  )

  private lazy val dataStreamSets: List[List[String]] = List(
    List("kibana_sample_data_logs"),
    List("logs-app"),
    List(".kibana-reporting-.kibana"),
    List("*"),
    List("_all"),
    List("logs-*"),
    List("kibana_sample_data_logs", "logs-app")
  )

  private lazy val restRequests: List[MockRestRequest] = {
    val paths = List(
      UriPath.from(NonEmptyString.unsafeFrom("/undefined")),
      UriPath.from(NonEmptyString.unsafeFrom("/.kibana/url/abc")),
      UriPath.from(NonEmptyString.unsafeFrom("/.custom_kibana/index-pattern/abc")),
      UriPath.from(NonEmptyString.unsafeFrom("/_template/abc")),
      UriPath.userMetadataPath
    )
    val kibanaRequestPaths = List(
      None,
      Some("/app/management/data/index_management/indices"),
      Some("/app/management/kibana/tags/"),
      Some("/app/discover")
    )
    for {
      path <- paths
      kibanaRequestPath <- kibanaRequestPaths
    } yield MockRestRequest(
      path = path,
      allHeaders = UniqueList.from(kibanaRequestPath.map(header("x-ror-kibana-request-path", _)).toList)
    )
  }

  private def blockContexts(block: Block, action: Action, restRequest: MockRestRequest): List[BlockContext] = {
    val generalIndexContexts = (List.empty[String] :: indexSets).map { indices =>
      val requestedIndices = toRequestedIndices(indices)
      val requestContext = MockRequestContext.indices.copy(
        restRequest = restRequest,
        action = action,
        filteredIndices = requestedIndices
      )
      GeneralIndexRequestBlockContext(
        block = block,
        requestContext = requestContext,
        blockMetadata = BlockMetadata.from(requestContext),
        responseHeaders = Set.empty,
        responseTransformations = List.empty,
        filteredIndices = requestedIndices,
        allAllowedIndices = Set.empty,
        allAllowedClusters = Set.empty
      )
    }
    val backingIndicesOptions =
      BackingIndices.IndicesNotInvolved ::
        List(List("logs-1"), List(".kibana"), List(".ds-.kibana-reporting-.kibana-2025.01.01-000001")).map { indices =>
          BackingIndices.IndicesInvolved(toRequestedIndices(indices), Set(ClusterIndexName.Local.wildcard))
        }
    val dataStreamContexts = for {
      dataStreams <- dataStreamSets
      backingIndices <- backingIndicesOptions
    } yield {
      val requestedDataStreams = dataStreams.flatMap(DataStreamName.fromString).toCovariantSet
      val requestContext = MockRequestContext.dataStreams.copy(
        restRequest = restRequest,
        action = action,
        dataStreams = requestedDataStreams
      )
      DataStreamRequestBlockContext(
        block = block,
        requestContext = requestContext,
        blockMetadata = BlockMetadata.from(requestContext),
        responseHeaders = Set.empty,
        responseTransformations = List.empty,
        dataStreams = requestedDataStreams,
        backingIndices = backingIndices
      )
    }
    val nonIndexContext = {
      val requestContext = MockRequestContext.nonIndices.copy(restRequest = restRequest, action = action)
      GeneralNonIndexRequestBlockContext(
        block = block,
        requestContext = requestContext,
        blockMetadata = BlockMetadata.from(requestContext),
        responseHeaders = Set.empty,
        responseTransformations = List.empty
      )
    }
    nonIndexContext :: generalIndexContexts ++ dataStreamContexts
  }

  private def toRequestedIndices(indices: List[String]): Set[RequestedIndex[ClusterIndexName]] =
    indices.map(index => requestedIndex(NonEmptyString.unsafeFrom(index))).toCovariantSet

  private def describe(blockContext: BlockContext): String = {
    val restRequest = blockContext.requestContext.restRequest
    val kibanaRequestPath = blockContext.requestContext.headers.kibanaRequestPath.map(_.value).getOrElse("none")
    s"${blockContext.getClass.getSimpleName}, indices ${blockContext.indices.map(_.name.stringify).mkString(",")}, " +
      s"data streams ${blockContext.dataStreams.map(_.stringify).mkString(",")}, path ${restRequest.path.value.value}, " +
      s"kibana request path $kibanaRequestPath"
  }

}

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
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaRequest.Target
import tech.beshu.ror.accesscontrol.blocks.{Block, BlockContext}
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.mocks.{MockRequestContext, MockRestRequest}
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.TestsUtils.*
import tech.beshu.ror.utils.uniquelist.UniqueList

// Temporary: compares the access table with the decision of `BaseKibanaRule` before the refactor
// (`LegacyKibanaDecision`, with the action groups of that time) for a matrix of requests. Only the changes of the
// action groups can make a difference (see `isExpectedDifference`). Remove both when the review of the refactor is
// done.
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
        val request = new KibanaRequest(blockContext, kibanaIndex, rorIndex)
        val before = legacyDecisions(access).shouldMatch((blockContext, kibanaIndex))
        val after = KibanaAccessTable.isAllowed(access, request)
        Option.when(before != after) {
          val difference = s"$access, ${action.value}, kibana index ${kibanaIndex.stringify}, " +
            s"${describe(blockContext)}: before $before, after $after"
          (isExpectedDifference(access, action, request, before, after), difference)
        }
      }
      val (expectedDifferences, unexpectedDifferences) = decisions.flatten.partition(_._1)
      decisions.size should be > 100000
      info(s"${decisions.size} decisions, ${expectedDifferences.size} expected differences")
      withClue(unexpectedDifferences.take(30).map(_._2).mkString("\n", "\n", "\n")) {
        unexpectedDifferences.size should be(0)
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

  // The changes of the action groups after the refactor. With the ES action lists (ES 5.3.3 to 9.5.6), their effect
  // on the real actions is:
  // - `indices:admin/create_index` is not an ES action;
  // - of `indices:admin/aliases/*`, `rw` keeps no action: `indices:admin/aliases/get` was a read action already, and
  //   `indices:admin/aliases/exists` is a read action now (all levels, all targets);
  // - `rw` allows `indices:data/write/simulate/bulk*` and `indices:data/write/reindex*` also with no indices;
  // - on the Kibana indices, `rw` and `admin` allow only the document writes of `rw`, not all `indices:data/write/*`.
  private def isExpectedDifference(
      access: KibanaAccess,
      action: Action,
      request: KibanaRequest,
      before: Boolean,
      after: Boolean
  ): Boolean = {
    val isRemovedAction = removedActions.`match`(action) && !keptAliasActions.`match`(action)
    val isNewReadAction = newReadActions.`match`(action)
    val isNewRwActionWithNoIndices =
      access == KibanaAccess.RW && request.target == Target.NoIndices && newRwActions.`match`(action)
    val isOtherDocumentWriteOnKibanaIndices =
      (access == KibanaAccess.RW || access == KibanaAccess.Admin) && request.target == Target.KibanaIndices &&
        documentWrites.`match`(action) && !rwActionPatternsMatcher.`match`(action)
    (before && !after && (isRemovedAction || isOtherDocumentWriteOnKibanaIndices)) ||
    (!before && after && (isNewReadAction || isNewRwActionWithNoIndices))
  }

  private lazy val removedActions = actionsMatcher("indices:admin/create_index", "indices:admin/aliases/*")
  private lazy val keptAliasActions = actionsMatcher("indices:admin/aliases/exists", "indices:admin/aliases/get")
  private lazy val newReadActions = actionsMatcher("indices:admin/aliases/exists")
  private lazy val newRwActions = actionsMatcher("indices:data/write/simulate/bulk*", "indices:data/write/reindex*")
  private lazy val documentWrites = actionsMatcher("indices:data/write/*")

  private def actionsMatcher(patterns: String*): PatternsMatcher[Action] =
    PatternsMatcher.create(patterns.map(Action.apply).toSet)

  private lazy val actions: List[Action] = {
    val patterns = List(
      roActionPatternsMatcher,
      rwActionPatternsMatcher,
      clusterActionPatternsMatcher,
      adminActionPatternsMatcher,
      nonStrictActions,
      LegacyKibanaDecision.Matchers.roActionPatternsMatcher,
      LegacyKibanaDecision.Matchers.rwActionPatternsMatcher,
      LegacyKibanaDecision.Matchers.clusterActionPatternsMatcher,
      LegacyKibanaDecision.Matchers.adminActionPatternsMatcher,
      LegacyKibanaDecision.Matchers.nonStrictActions,
      LegacyKibanaDecision.Matchers.indicesWriteAction
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
      "indices:monitor/data_stream/stats",
      "indices:admin/aliases",
      "indices:admin/aliases/exists",
      "indices:admin/aliases/get",
      "indices:data/write/reindex",
      "indices:data/write/reindex/resume",
      "indices:data/write/simulate/bulk",
      "indices:data/write/otlp/logs",
      "indices:data/write/prometheus/remote_write",
      "indices:data/write/xpack/connector/put"
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

  private lazy val nonStrictPaths: List[UriPath] = List(
    UriPath.from(NonEmptyString.unsafeFrom("/.kibana/url/abc")),
    UriPath.from(NonEmptyString.unsafeFrom("/.custom_kibana/index-pattern/abc")),
    UriPath.from(NonEmptyString.unsafeFrom("/_template/abc"))
  )

  private lazy val restRequests: List[MockRestRequest] = {
    val paths =
      UriPath.from(NonEmptyString.unsafeFrom("/undefined")) :: UriPath.userMetadataPath :: nonStrictPaths
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
    // A data stream request with backing indices (`_modify`) has a `/_data_stream/...` path, never a non-strict path
    // of a Kibana document. The access table gives such a request the Kibana sample data target before the Kibana
    // indices one, so these impossible combinations would differ for `ro` and `api_only`.
    val backingIndicesOptions =
      BackingIndices.IndicesNotInvolved :: (
        if (nonStrictPaths.contains(restRequest.path)) Nil
        else
          List(List("logs-1"), List(".kibana"), List(".ds-.kibana-reporting-.kibana-2025.01.01-000001")).map {
            indices =>
              BackingIndices.IndicesInvolved(toRequestedIndices(indices), Set(ClusterIndexName.Local.wildcard))
          }
      )
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

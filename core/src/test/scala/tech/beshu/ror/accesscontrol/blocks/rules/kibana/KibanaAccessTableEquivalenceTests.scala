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

import scala.io.{Codec, Source}

// Temporary: compares the access table with the decision of `BaseKibanaRule` before the refactor
// (`LegacyKibanaDecision`, with the action groups of that time), for a matrix of requests and for the real ES actions.
// A request that was rejected before can be allowed now only as a read of its access level (see `isAllowedGain`). The
// tests print the other changes for the review. Remove both when the review of the refactor is done.
class KibanaAccessTableEquivalenceTests extends AnyWordSpec with BlockContextAssertion with BeforeAndAfterAll {

  private val kibanaRulesPackage = "tech.beshu.ror.accesscontrol.blocks.rules.kibana"
  private val logLevelBefore = LogManager.getLogger(kibanaRulesPackage).getLevel

  // The decision before the refactor writes about ten debug lines for each request
  override def beforeAll(): Unit = Configurator.setLevel(kibanaRulesPackage, Level.INFO)

  override def afterAll(): Unit = Configurator.setLevel(kibanaRulesPackage, logLevelBefore)

  "The kibana access table" should {
    "allow no more than before, except the reads of each access level" in {
      val block = mock[Block]
      val differences = for {
        restRequest <- restRequests
        action <- actions
        blockContext <- blockContexts(block, action, restRequest)
        kibanaIndex <- kibanaIndices
        access <- accessLevels
        request = new KibanaRequest(blockContext, kibanaIndex)
        before = legacyDecisions(access).shouldMatch((blockContext, kibanaIndex))
        after = KibanaAccessTable.isAllowed(access, request)
        if before != after
      } yield (access, action, request, before, after, describe(blockContext))
      val (gains, losses) = differences.partition { case (_, _, _, before, after, _) => !before && after }
      val forbiddenGains = gains.filterNot { case (access, action, request, _, _, _) =>
        isAllowedGain(access, action, request)
      }
      info(s"${gains.size} gains (all reads), ${losses.size} losses")
      withClue(
        forbiddenGains
          .take(30)
          .map { case (access, action, _, _, _, context) =>
            s"$access, ${action.value}, $context"
          }
          .mkString("\n", "\n", "\n")
      ) {
        forbiddenGains.size should be(0)
      }
    }
    "report the changes for the real ES actions" in {
      val block = mock[Block]
      val restRequest = MockRestRequest(path = UriPath.from(NonEmptyString.unsafeFrom("/undefined")))
      val kibanaIndex = kibanaIndexName(".kibana")
      val differences = for {
        action <- esActions
        blockContext <- blockContexts(block, action, restRequest).filter(isReportedBlockContext)
        access <- accessLevels
        request = new KibanaRequest(blockContext, kibanaIndex)
        before = legacyDecisions(access).shouldMatch((blockContext, kibanaIndex))
        after = KibanaAccessTable.isAllowed(access, request)
        if before != after
      } yield (action, access, request, before, after)
      info(s"${esActions.size} ES actions, ${differences.size} differences")
      differences.groupBy(_._1).toList.sortBy(_._1.value).foreach { case (action, actionDifferences) =>
        val changes = actionDifferences.map { case (_, access, request, before, after) =>
          s"$access ${request.target.name} ${if (after) "allowed" else "rejected"} now"
        }
        info(s"REPORT ${action.value}: ${changes.distinct.mkString(", ")}")
      }
      val forbiddenGains = differences.collect {
        case (action, access, request, false, true) if !isAllowedGain(access, action, request) =>
          s"${action.value} $access"
      }
      forbiddenGains should be(List.empty)
    }
  }

  // A read of the access level: the reads of all levels, the user metadata and audit event requests of all levels, and
  // the cluster management reads of `rw` and `admin`
  private def isAllowedGain(access: KibanaAccess, action: Action, request: KibanaRequest): Boolean = {
    val isRwOrAdmin = access == KibanaAccess.RW || access == KibanaAccess.Admin
    readActions.`match`(action) ||
    request.isUserMetadataRequest ||
    request.isAuditEventRequest ||
    (isRwOrAdmin && clusterManagementReadActions.`match`(action)) ||
    (access == KibanaAccess.Admin && rorAdminActions.`match`(action))
  }

  // The union of the ES action lists of `readonlyrest-docs/actionstrings`, ES 6.7.0 to 9.5.6
  private lazy val esActions: List[Action] =
    Source
      .fromResource("kibana_access_table/es_action_strings.txt")(Codec.UTF8)
      .getLines()
      .filter(_.nonEmpty)
      .map(Action.apply)
      .toList

  // The report uses three block contexts: no indices, the Kibana index, a data index
  private def isReportedBlockContext(blockContext: BlockContext): Boolean = blockContext match {
    case _: GeneralNonIndexRequestBlockContext => true
    case _: GeneralIndexRequestBlockContext    =>
      List(Set(".kibana"), Set("logs-1")).contains(blockContext.indices.map(_.name.stringify))
    case _ => false
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
      readActions,
      writeActions,
      clusterManagementReadActions,
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

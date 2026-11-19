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

import com.github.benmanes.caffeine.cache.{Cache, Caffeine}
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaActionMatchers.*
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaRequest.*
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.KibanaIndexName.*
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged

import java.util.regex.Pattern
import scala.concurrent.ExecutionContext.global

// What the kibana rule needs to know about a request: the target of the request (the columns of the access table),
// and the facts about its action and paths that the cells of the table use.
private[kibana] final class KibanaRequest(blockContext: BlockContext, kibanaIndex: KibanaIndexName) {

  private val action = blockContext.requestContext.action

  lazy val target: Target = {
    val indices = blockContext.indices
    if (isOneKibanaSampleDataSet) Target.KibanaSampleData
    else if (indices.isEmpty) Target.NoIndices
    else if (indices.forall(_.name.isRelatedToKibanaIndex(kibanaIndex))) Target.KibanaIndices
    else Target.DataIndices
  }

  def isReadAction: Boolean = readActions.`match`(action)

  def isWriteAction: Boolean = writeActions.`match`(action)

  def isClusterManagementReadAction: Boolean = clusterManagementReadActions.`match`(action)

  def isRorAdminAction: Boolean = rorAdminActions.`match`(action)

  def isSampleDataAction: Boolean = sampleDataActions.`match`(action)

  def isNonStrictWrite: Boolean =
    NonStrictWrite.matches(action, blockContext.requestContext.restRequest.path, kibanaIndex)

  def isUserMetadataRequest: Boolean = action == Action.RorAction.RorUserMetadataAction

  // The request uses the full name of one Kibana sample data set and no other name. A data stream request uses the name
  // of its data stream, and its backing indices do not count. The other requests use their indices, aliases and
  // template names.
  private lazy val isOneKibanaSampleDataSet: Boolean = {
    def isOneSampleDataStream = blockContext.dataStreams.toList match {
      case dataStream :: Nil => kibanaSampleDataSetNames.contains(dataStream.stringify)
      case _                 => false
    }
    def isOneSampleDataIndexOrTemplate =
      (blockContext.indices.map(_.stringify) ++ blockContext.templateNames.map(_.stringify)).toList match {
        case name :: Nil => kibanaSampleDataSetNames.contains(name)
        case _           => false
      }
    isOneSampleDataStream || isOneSampleDataIndexOrTemplate
  }

}

private[ror] object KibanaRequest {

  sealed trait Target

  object Target {
    case object KibanaIndices extends Target
    case object DataIndices extends Target
    case object KibanaSampleData extends Target
    case object NoIndices extends Target
  }

  // The writes of the Kibana UI that the read only access levels also do: a non-strict action to a path of the main
  // Kibana index for the "Discover" state, short URLs, data views, telemetry or new config documents.
  private object NonStrictWrite {

    def matches(action: Action, path: UriPath, kibanaIndex: KibanaIndexName): Boolean =
      nonStrictActions.`match`(action) && pathPatternFor(kibanaIndex).matcher(path.value.value).find()

    private val pathsRegexTemplate =
      "^/@kibana_index/(url|config/.*/_create|index-pattern|doc/index-pattern.*|doc/url.*)/.*|^/@kibana_index/doc/telemetry.*|^/@kibana_index/(_update/index-pattern.*|_update/url.*)|^/@kibana_index/_create/(url:.*)"

    // The pattern varies only by the Kibana index name (typically a single, static value), so it is cached per Kibana
    // index instead of being compiled for each request.
    private val pathPatternCache: Cache[KibanaIndexName, Pattern] =
      doPrivileged {
        Caffeine
          .newBuilder()
          .executor(global)
          .maximumSize(1000)
          .build[KibanaIndexName, Pattern]()
      }

    private def pathPatternFor(kibanaIndexName: KibanaIndexName): Pattern = {
      // Caffeine's atomic loader guarantees the pattern is compiled at most once per
      // Kibana index even under concurrent access.
      pathPatternCache.get(
        kibanaIndexName,
        _ =>
          Pattern.compile(
            // `@kibana_index` only ever appears as a literal path segment in the template, so the index
            // name is quoted: any regex metacharacter in it (e.g. the `.` in `.kibana`) must match
            // literally, never act as a pattern. The quoting also makes every index name compile.
            pathsRegexTemplate.replace("@kibana_index", Pattern.quote(kibanaIndexName.stringify))
          )
      )
    }

  }

}

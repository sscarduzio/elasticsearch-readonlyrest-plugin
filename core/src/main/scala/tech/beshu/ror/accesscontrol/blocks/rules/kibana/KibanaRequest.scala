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
import scala.util.Try

// What the kibana rule needs to know about a request: the target of the request (the columns of the access table),
// and the facts about its action and paths that the cells of the table use.
private[kibana] final class KibanaRequest(blockContext: BlockContext, kibanaIndex: KibanaIndexName) {

  private val action = blockContext.requestContext.action

  lazy val target: Target = {
    val indices = blockContext.indices
    if (isOneKibanaSampleDataIndexOrDataStream) Target.KibanaSampleData
    else if (indices.isEmpty) Target.NoIndices
    else if (indices.forall(_.name.isRelatedToKibanaIndex(kibanaIndex))) Target.KibanaIndices
    else Target.DataIndices
  }

  def isReadAction: Boolean = readActions.`match`(action)

  def isWriteAction: Boolean = writeActions.`match`(action)

  def isClusterManagementReadAction: Boolean = clusterManagementReadActions.`match`(action)

  def isRorAdminAction: Boolean = rorAdminActions.`match`(action)

  def isNonStrictAction: Boolean = nonStrictActions.`match`(action)

  // The writes of the Kibana UI that the read only levels need: a non-strict path and a non-strict action
  def isNonStrictWrite: Boolean = isNonStrictPath && isNonStrictAction

  def isUserMetadataRequest: Boolean = action == Action.RorAction.RorUserMetadataAction

  private lazy val isOneKibanaSampleDataIndexOrDataStream: Boolean = {
    def isOneSampleDataIndex = blockContext.indices.toList match {
      case index :: Nil => kibanaSampleDataIndexMatcher.`match`(index.name)
      case _            => false
    }
    def isOneSampleDataStream = blockContext.dataStreams.toList match {
      case dataStream :: Nil => kibanaSampleDataStreamMatcher.`match`(dataStream)
      case _                 => false
    }
    isOneSampleDataIndex || isOneSampleDataStream
  }

  // The paths of the writes that the Kibana UI does also for the read only access levels: the "Discover" state, short
  // URLs, data views, telemetry and new config documents
  private lazy val isNonStrictPath: Boolean =
    nonStrictAllowedPathsPatternFor(kibanaIndex) match {
      case Some(paths) => paths.matcher(blockContext.requestContext.restRequest.path.value.value).find()
      case None        => false
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

  // The regex only varies by the Kibana index name (typically a single, static value),
  // so the compiled Pattern is cached per Kibana index instead of being recompiled on
  // every non-strict path check.
  private val nonStrictAllowedPathsRegexTemplate =
    "^/@kibana_index/(url|config/.*/_create|index-pattern|doc/index-pattern.*|doc/url.*)/.*|^/@kibana_index/doc/telemetry.*|^/@kibana_index/(_update/index-pattern.*|_update/url.*)|^/@kibana_index/_create/(url:.*)"

  private val nonStrictAllowedPathsPatternCache: Cache[KibanaIndexName, Option[Pattern]] =
    doPrivileged {
      Caffeine
        .newBuilder()
        .executor(global)
        .maximumSize(1000)
        .build[KibanaIndexName, Option[Pattern]]()
    }

  private def nonStrictAllowedPathsPatternFor(kibanaIndexName: KibanaIndexName): Option[Pattern] = {
    // Caffeine's atomic loader guarantees the pattern is compiled at most once per
    // Kibana index even under concurrent access. `None` (the compile-failure sentinel)
    // is a valid non-null value, so it is stored and retrieved like any other entry.
    nonStrictAllowedPathsPatternCache.get(
      kibanaIndexName,
      _ =>
        Try(
          Pattern.compile(
            // `@kibana_index` only ever appears as a literal path segment in the template, so the index
            // name is quoted: any regex metacharacter in it (e.g. the `.` in `.kibana`) must match
            // literally, never act as a pattern. Without quoting, `.` would match any char and names
            // containing `(`, `[`, `\`, ... would compile to a wrong pattern or fail to compile.
            nonStrictAllowedPathsRegexTemplate.replace("@kibana_index", Pattern.quote(kibanaIndexName.stringify))
          )
        ).toOption
    )
  }

}

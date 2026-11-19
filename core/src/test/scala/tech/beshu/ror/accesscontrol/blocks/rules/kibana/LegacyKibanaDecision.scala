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

import cats.Id
import cats.data.ReaderT
import com.github.benmanes.caffeine.cache.{Cache, Caffeine}
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.LegacyKibanaDecision.*
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.LegacyKibanaDecision.Matchers.*
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.KibanaAccess.*
import tech.beshu.ror.accesscontrol.domain.KibanaIndexName.*
import tech.beshu.ror.accesscontrol.matchers.PatternsMatcher
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.AccessControllerHelper.doPrivileged
import tech.beshu.ror.utils.RefinedUtils.*
import tech.beshu.ror.utils.RequestIdAwareLogging

import java.util.regex.Pattern
import scala.concurrent.ExecutionContext.global
import scala.util.Try

// Temporary: the decision of `BaseKibanaRule` before the access table, to compare the two in
// `KibanaAccessTableEquivalenceTests`. Remove both when the review of the refactor is done.
final class LegacyKibanaDecision(val settings: Settings) extends RequestIdAwareLogging {

  import LegacyKibanaDecision.*

  lazy val shouldMatch: ProcessingContext = {
    isUnrestrictedAccessConfigured ||
    isUserMetadataRequest ||
    isDevNullKibanaRelated ||
    isRoAction ||
    isClusterAction ||
    emptyIndicesMatch ||
    isKibanaSimpleData ||
    isRoNonStrictCase ||
    isAdminAccessEligible ||
    isKibanaIndexRequest
  }

  private lazy val isUnrestrictedAccessConfigured = ProcessingContext.create { (bc, _) =>
    given BlockContext = bc
    val result = settings.access === KibanaAccess.Unrestricted
    logger.debug(s"Is unrestricted access configured? ${result.show}")
    result
  }

  private lazy val isUserMetadataRequest = ProcessingContext.create { (bc, _) =>
    given BlockContext = bc
    val requestPath = bc.requestContext.restRequest.path
    val result = requestPath.isUserMetadataPath
    logger.debug(s"Is is a ReadonlyREST's user metadata request? ${result.show}")
    result
  }

  private lazy val isKibanaIndexRequest = {
    kibanaCanBeModified &&
    isTargetingKibana &&
    (isRoAction || isRwAction || isIndicesWriteAction)
  }

  private lazy val isAdminAccessEligible: ProcessingContext = {
    isAdminAccessConfigured && isAdminAction && isRequestAllowedForAdminAccess
  }

  private lazy val isAdminAccessConfigured = ProcessingContext.create { (bc, _) =>
    given BlockContext = bc
    val result = settings.access === KibanaAccess.Admin
    logger.debug(s"Is the admin access configured in the rule? ${result.show}")
    result
  }

  private lazy val isRequestAllowedForAdminAccess = {
    doesRequestContainNoIndices ||
    isRequestRelatedToRorIndex ||
    isRequestRelatedToIndexManagementPath ||
    isRequestRelatedToTagsPath
  }

  private lazy val doesRequestContainNoIndices = ProcessingContext.create { (bc, _) =>
    given BlockContext = bc
    val result = bc.indices.isEmpty
    logger.debug(s"Does request contain no indices? ${result.show}")
    result
  }

  private lazy val isRoNonStrictCase = {
    isTargetingKibana &&
    isAccessOtherThanRoStrictConfigured &&
    kibanaCannotBeModified &&
    isNonStrictAllowedPath &&
    isNonStrictAction
  }

  private lazy val isAccessOtherThanRoStrictConfigured = ProcessingContext.create { (bc, _) =>
    given BlockContext = bc
    val result = settings.access =!= ROStrict
    logger.debug(s"Is access other than ROStrict configured? ${result.show}")
    result
  }

  private lazy val isKibanaSimpleData = {
    kibanaCanBeModified && (isRelatedToKibanaSampleDataIndex || isRelatedToKibanaSampleDataStream)
  }

  private lazy val emptyIndicesMatch = {
    doesRequestContainNoIndices && {
      (kibanaCanBeModified && isRwAction) ||
      (isAdminAccessConfigured && isAdminAction)
    }
  }

  // Save UI state in discover & Short urls
  private lazy val isNonStrictAllowedPath = ProcessingContext.create { (bc, kibanaIndexName) =>
    val path = bc.requestContext.restRequest.path
    val result = nonStrictAllowedPathsPatternFor(kibanaIndexName) match {
      case Some(paths) => paths.matcher(path.value.value).find()
      case None        => false
    }
    given BlockContext = bc
    logger.debug(s"Is non strict allowed path? ${result.show}")
    result
  }

  private lazy val isTargetingKibana = ProcessingContext.create { (bc, kibanaIndexName) =>
    val result = if (bc.indices.nonEmpty) {
      bc.indices.forall(_.name.isRelatedToKibanaIndex(kibanaIndexName))
    } else {
      false
    }
    given BlockContext = bc
    logger.debug(s"Is targeting Kibana? ${result.show}")
    result
  }

  private lazy val isRequestRelatedToRorIndex: ProcessingContext = {
    isRelatedToSingleIndex(settings.rorIndex.toLocal)
  }

  private lazy val isRequestRelatedToIndexManagementPath: ProcessingContext = {
    isRequestRelatedToTagsPath("index_management")
  }

  private lazy val isRequestRelatedToTagsPath: ProcessingContext = {
    isRequestRelatedToTagsPath("tags")
  }

  private def isRequestRelatedToTagsPath(pathPart: String) = ProcessingContext.create { (bc, _) =>
    given BlockContext = bc
    val result = bc.requestContext.headers.kibanaRequestPath.exists(_.value.contains(s"/$pathPart/"))
    logger.debug(s"Does kibana request contains '${pathPart.show}' in path? ${result.show}")
    result
  }

  // Allow other actions if devnull is targeted to readers and writers
  private lazy val isDevNullKibanaRelated = {
    isRelatedToSingleIndex(devNullKibana.underlying)
  }

  private def isRelatedToSingleIndex(index: ClusterIndexName) = ProcessingContext.create { (bc, _) =>
    val result = bc.indices.headOption match {
      case Some(requestedIndex) if requestedIndex.name == index => true
      case Some(_) | None                                       => false
    }
    given BlockContext = bc
    logger.debug(s"Is related to single index '${index.nonEmptyStringify}'? ${result.show}")
    result
  }

  private lazy val isRelatedToKibanaSampleDataIndex = ProcessingContext.create { (bc, _) =>
    val result = bc.indices.toList match {
      case Nil         => false
      case head :: Nil => kibanaSampleDataIndexMatcher.`match`(head.name)
      case _           => false
    }
    given BlockContext = bc
    logger.debug(s"Is related to Kibana sample data index? ${result.show}")
    result
  }

  private lazy val isRelatedToKibanaSampleDataStream = ProcessingContext.create { (bc, _) =>
    val result = bc.dataStreams.toList match {
      case Nil         => false
      case head :: Nil => kibanaSampleDataStreamMatcher.`match`(head)
      case _           => false
    }
    given BlockContext = bc
    logger.debug(s"Is related to Kibana sample data index? ${result.show}")
    result
  }

  private lazy val isRoAction = ProcessingContext.create { (bc, _) =>
    val result = roActionPatternsMatcher.`match`(bc.requestContext.action)
    given BlockContext = bc
    logger.debug(s"Is RO action? ${result.show}")
    result
  }

  private lazy val isClusterAction = ProcessingContext.create { (bc, _) =>
    val result = clusterActionPatternsMatcher.`match`(bc.requestContext.action)
    given BlockContext = bc
    logger.debug(s"Is Cluster action? ${result.show}")
    result
  }

  private lazy val isRwAction = ProcessingContext.create { (bc, _) =>
    val result = rwActionPatternsMatcher.`match`(bc.requestContext.action)
    given BlockContext = bc
    logger.debug(s"Is RW action? ${result.show}")
    result
  }

  private lazy val isAdminAction = ProcessingContext.create { (bc, _) =>
    val result = adminActionPatternsMatcher.`match`(bc.requestContext.action)
    given BlockContext = bc
    logger.debug(s"Is Admin action? ${result.show}")
    result
  }

  private lazy val isNonStrictAction = ProcessingContext.create { (bc, _) =>
    val result = nonStrictActions.`match`(bc.requestContext.action)
    given BlockContext = bc
    logger.debug(s"Is non strict action? ${result.show}")
    result
  }

  private lazy val isIndicesWriteAction = ProcessingContext.create { (bc, _) =>
    val result = indicesWriteAction.`match`(bc.requestContext.action)
    given BlockContext = bc
    logger.debug(s"Is indices write action? ${result.show}")
    result
  }

  private lazy val kibanaCannotBeModified = !kibanaCanBeModified

  private lazy val kibanaCanBeModified = ProcessingContext.create { (bc, _) =>
    val result = settings.access match {
      case RO | ROStrict | ApiOnly   => false
      case RW | Admin | Unrestricted => true
    }
    given BlockContext = bc
    logger.debug(s"Can Kibana be modified? ${result.show}")
    result
  }

}

object LegacyKibanaDecision {

  private val devNullKibana: KibanaIndexName = KibanaIndexName(
    ClusterIndexName.Local(IndexName.Full(nes(".kibana-devnull")))
  )

  // The action groups of `KibanaActionMatchers` before the refactor
  object Matchers {

    val roActionPatternsMatcher: PatternsMatcher[Action] = PatternsMatcher.create {
      Set(
        "indices:admin/exists",
        "indices:admin/mappings/fields/get*",
        "indices:admin/mappings/get*",
        "indices:admin/validate/query",
        "indices:admin/get",
        "indices:admin/refresh*",
        "indices:data/read/*",
        "indices:admin/resolve/*",
        "indices:admin/aliases/get",
        "indices:admin/data_stream/get",
        "indices:admin/data_stream/lifecycle/get",
        "cluster:admin/component_template/get",
        "indices:admin/index_template/get",
        "cluster:admin/scripts/painless/context",
        "indices:admin/*/explain",
        "indices:data/read/xpack/rollup/get/*",
        "indices:monitor/*",
      ).map(Action.apply)
    }

    val rwActionPatternsMatcher: PatternsMatcher[Action] = PatternsMatcher.create {
      Action.RorAction.writeActions ++
        Set(
          "indices:admin/create",
          "indices:admin/create_index",
          "indices:admin/mapping/put",
          "indices:data/write/delete*",
          "indices:data/write/index",
          "indices:data/write/update*",
          "indices:data/write/bulk*",
          "indices:admin/template/*",
          "cluster:admin/settings/*",
          "indices:admin/aliases/*",
          "indices:admin/data_stream/*",
          "indices:admin/index_template/*",
          "cluster:admin/component_template/*",
          "cluster:admin/migration/get_system_feature",
          "cluster:admin/scripts/painless/execute"
        ).map(Action.apply)
    }

    val clusterActionPatternsMatcher: PatternsMatcher[Action] = PatternsMatcher.create {
      Set(
        "cluster:monitor/*",
        "cluster:*/xpack/*",
        "indices:admin/template/get*",
        "cluster:*/info",
        "cluster:*/get",
        "cluster:*/simulate"
      ).map(Action.apply)
    }

    val adminActionPatternsMatcher: PatternsMatcher[Action] = PatternsMatcher.create {
      Action.RorAction.adminActions ++
        Set(
          "cluster:internal_ror/*",
          "indices:data/write/*", // <-- DEPRECATED!
          "indices:admin/create",
          "indices:admin/create_index",
          "indices:admin/ilm/*",
          "cluster:admin/ingest/pipeline/put",
          "cluster:admin/ingest/pipeline/delete",
          "indices:admin/data_stream/get",
          "indices:admin/settings/update",
          "cluster:admin/ilm/put",
          "cluster:admin/ilm/delete",
          "cluster:admin/repository/put",
          "cluster:admin/repository/delete",
          "indices:data/write/update/byquery",
          "indices:admin/data_stream/delete",
          "cluster:admin/component_template/get",
          "cluster:admin/component_template/delete",
          "cluster:admin/component_template/put",
          "indices:admin/delete",
          "indices:admin/index_template/get",
          "indices:admin/index_template/delete",
          "indices:admin/index_template/put",
          "indices:admin/index_template/simulate",
          "cluster:admin/slm/put",
          "cluster:admin/slm/execute",
          "cluster:admin/slm/delete"
        ).map(Action.apply)
    }

    val nonStrictActions: PatternsMatcher[Action] = PatternsMatcher.create(
      Set(
        Action("indices:data/write/*"),
        Action("indices:admin/template/put")
      )
    )

    val indicesWriteAction: PatternsMatcher[Action] = PatternsMatcher.create(Set(Action("indices:data/write/*")))

    val kibanaSampleDataIndexMatcher: PatternsMatcher[ClusterIndexName] = PatternsMatcher.create(
      Set(ClusterIndexName.Local(IndexName.Pattern.unsafeFromNes(nes("kibana_sample_data_*"))))
    )

    val kibanaSampleDataStreamMatcher: PatternsMatcher[DataStreamName] = PatternsMatcher.create(
      Set(DataStreamName.Pattern.fromNes(nes("kibana_sample_data_*")))
    )

  }

  final class Settings(val access: KibanaAccess, val rorIndex: RorSettingsIndex)

  // The regex only varies by the Kibana index name (typically a single, static value),
  // so the compiled Pattern is cached per Kibana index instead of being recompiled on
  // every non-strict path check.
  private val nonStrictAllowedPathsRegexTemplate =
    "^/@kibana_index/(url|config/.*/_create|index-pattern|doc/index-pattern.*|doc/url.*)/.*|^/_template/.*|^/@kibana_index/doc/telemetry.*|^/@kibana_index/(_update/index-pattern.*|_update/url.*)|^/@kibana_index/_create/(url:.*)"

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

  type ProcessingContext = ReaderT[Id, (BlockContext, KibanaIndexName), Boolean]

  object ProcessingContext {
    def create(func: (BlockContext, KibanaIndexName) => Boolean): ProcessingContext =
      ReaderT[Id, (BlockContext, KibanaIndexName), Boolean] { case (bc, i) => func(bc, i) }
  }

  implicit class ProcessingContextBooleanOps(val context1: ProcessingContext) extends AnyVal {

    def &&(context2: ProcessingContext): ProcessingContext =
      ProcessingContext.create { case (blockContext, kibanaIndexName) =>
        context1(blockContext, kibanaIndexName) && context2(blockContext, kibanaIndexName)
      }

    def ||(context2: ProcessingContext): ProcessingContext =
      ProcessingContext.create { case (blockContext, kibanaIndexName) =>
        context1(blockContext, kibanaIndexName) || context2(blockContext, kibanaIndexName)
      }

    def unary_! : ProcessingContext =
      ProcessingContext.create { case (blockContext, kibanaIndexName) =>
        !context1(blockContext, kibanaIndexName)
      }

  }

}

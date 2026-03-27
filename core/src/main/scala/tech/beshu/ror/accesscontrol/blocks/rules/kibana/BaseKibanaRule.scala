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
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RegularRule
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.BaseKibanaRule.*
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.KibanaActionMatchers.*
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.KibanaAccess.*
import tech.beshu.ror.accesscontrol.domain.KibanaIndexName.*
import tech.beshu.ror.implicits.*
import tech.beshu.ror.utils.RequestIdAwareLogging

import java.util.regex.Pattern
import scala.util.Try

trait KibanaRelatedRule {
  this: Rule =>
}

abstract class BaseKibanaRule(val settings: Settings)
  extends RegularRule with KibanaRelatedRule with RequestIdAwareLogging {

  import BaseKibanaRule.*

  protected def shouldMatch: ProcessingContext = {
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

  private def isUnrestrictedAccessConfigured = ProcessingContext.create { (bc, _) =>
    given BlockContext = bc
    val result = settings.access === KibanaAccess.Unrestricted
    logger.debug(s"Is unrestricted access configured? ${result.show}")
    result
  }

  private def isUserMetadataRequest = ProcessingContext.create { (bc, _) =>
    given BlockContext = bc
    val requestPath = bc.requestContext.restRequest.path
    val result = requestPath.isCurrentUserMetadataPath || requestPath.isCurrentUserMetadataPath
    logger.debug(s"Is is a ReadonlyREST's user metadata request? ${result.show}")
    result
  }

  private def isKibanaIndexRequest = {
    kibanaCanBeModified &&
      isTargetingKibana &&
      (isRoAction || isRwAction || isIndicesWriteAction)
  }

  private def isAdminAccessEligible: ProcessingContext = {
    isAdminAccessConfigured && isAdminAction && isRequestAllowedForAdminAccess
  }

  private def isAdminAccessConfigured = ProcessingContext.create { (bc, _) =>
    given BlockContext = bc
    val result = settings.access === KibanaAccess.Admin
    logger.debug(s"Is the admin access configured in the rule? ${result.show}")
    result
  }

  private def isRequestAllowedForAdminAccess = {
    doesRequestContainNoIndices ||
      isRequestRelatedToRorIndex ||
      isRequestRelatedToIndexManagementPath ||
      isRequestRelatedToTagsPath
  }

  private def doesRequestContainNoIndices = ProcessingContext.create { (bc, _) =>
    given BlockContext = bc
    val result = bc.indices.isEmpty
    logger.debug(s"Does request contain no indices? ${result.show}")
    result
  }

  private def isRoNonStrictCase = {
    isTargetingKibana &&
      isAccessOtherThanRoStrictConfigured &&
      kibanaCannotBeModified &&
      isNonStrictAllowedPath &&
      isNonStrictAction
  }

  private def isAccessOtherThanRoStrictConfigured = ProcessingContext.create { (bc, _) =>
    given BlockContext = bc
    val result = settings.access =!= ROStrict
    logger.debug(s"Is access other than ROStrict configured? ${result.show}")
    result
  }

  private def isKibanaSimpleData = {
    kibanaCanBeModified && (isRelatedToKibanaSampleDataIndex || isRelatedToKibanaSampleDataStream)
  }

  private def emptyIndicesMatch = {
    doesRequestContainNoIndices && {
      (kibanaCanBeModified && isRwAction) ||
        (isAdminAccessConfigured && isAdminAction)
    }
  }

  // Save UI state in discover & Short urls
  private def isNonStrictAllowedPath = ProcessingContext.create { (bc, kibanaIndexName) =>
    val path = bc.requestContext.restRequest.path
    val nonStrictAllowedPaths = Try(Pattern.compile(
      "^/@kibana_index/(url|config/.*/_create|index-pattern|doc/index-pattern.*|doc/url.*)/.*|^/_template/.*|^/@kibana_index/doc/telemetry.*|^/@kibana_index/(_update/index-pattern.*|_update/url.*)|^/@kibana_index/_create/(url:.*)"
        .replace("@kibana_index", kibanaIndexName.stringify)
    )).toOption
    val result = nonStrictAllowedPaths match {
      case Some(paths) => paths.matcher(path.value.value).find()
      case None => false
    }
    given BlockContext = bc
    logger.debug(s"Is non strict allowed path? ${result.show}")
    result
  }

  private def isTargetingKibana = ProcessingContext.create { (bc, kibanaIndexName) =>
    val result = if (bc.indices.nonEmpty) {
      bc.indices.forall(_.name.isRelatedToKibanaIndex(kibanaIndexName))
    } else {
      false
    }
    given BlockContext = bc
    logger.debug(s"Is targeting Kibana? ${result.show}")
    result
  }

  private def isRequestRelatedToRorIndex: ProcessingContext = {
    isRelatedToSingleIndex(settings.rorIndex.toLocal)
  }

  private def isRequestRelatedToIndexManagementPath: ProcessingContext = {
    isRequestRelatedToTagsPath("index_management")
  }

  private def isRequestRelatedToTagsPath: ProcessingContext = {
    isRequestRelatedToTagsPath("tags")
  }

  private def isRequestRelatedToTagsPath(pathPart: String) = ProcessingContext.create { (bc, _) =>
    val result = bc
      .requestContext
      .restRequest
      .allHeaders
      .find(_.name === Header.Name.kibanaRequestPath)
      .exists(_.value.value.contains(s"/$pathPart/"))
    given BlockContext = bc
    logger.debug(s"Does kibana request contains '${pathPart.show}' in path? ${result.show}")
    result
  }

  // Allow other actions if devnull is targeted to readers and writers
  private def isDevNullKibanaRelated = {
    isRelatedToSingleIndex(devNullKibana.underlying)
  }

  private def isRelatedToSingleIndex(index: ClusterIndexName) = ProcessingContext.create { (bc, _) =>
    val result = bc.indices.headOption match {
      case Some(requestedIndex) if requestedIndex.name == index => true
      case Some(_) | None => false
    }
    given BlockContext = bc
    logger.debug(s"Is related to single index '${index.nonEmptyStringify}'? ${result.show}")
    result
  }

  private def isRelatedToKibanaSampleDataIndex = ProcessingContext.create { (bc, _) =>
    val result = bc.indices.toList match {
      case Nil => false
      case head :: Nil => kibanaSampleDataIndexMatcher.`match`(head.name)
      case _ => false
    }
    given BlockContext = bc
    logger.debug(s"Is related to Kibana sample data index? ${result.show}")
    result
  }

  private def isRelatedToKibanaSampleDataStream = ProcessingContext.create { (bc, _) =>
    val result = bc.dataStreams.toList match {
      case Nil => false
      case head :: Nil => kibanaSampleDataStreamMatcher.`match`(head)
      case _ => false
    }
    given BlockContext = bc
    logger.debug(s"Is related to Kibana sample data index? ${result.show}")
    result
  }

  private def isRoAction = ProcessingContext.create { (bc, _) =>
    val result = roActionPatternsMatcher.`match`(bc.requestContext.action)
    given BlockContext = bc
    logger.debug(s"Is RO action? ${result.show}")
    result
  }

  private def isClusterAction = ProcessingContext.create { (bc, _) =>
    val result = clusterActionPatternsMatcher.`match`(bc.requestContext.action)
    given BlockContext = bc
    logger.debug(s"Is Cluster action? ${result.show}")
    result
  }

  private def isRwAction = ProcessingContext.create { (bc, _) =>
    val result = rwActionPatternsMatcher.`match`(bc.requestContext.action)
    given BlockContext = bc
    logger.debug(s"Is RW action? ${result.show}")
    result
  }

  private def isAdminAction = ProcessingContext.create { (bc, _) =>
    val result = adminActionPatternsMatcher.`match`(bc.requestContext.action)
    given BlockContext = bc
    logger.debug(s"Is Admin action? ${result.show}")
    result
  }

  private def isNonStrictAction = ProcessingContext.create { (bc, _) =>
    val result = nonStrictActions.`match`(bc.requestContext.action)
    given BlockContext = bc
    logger.debug(s"Is non strict action? ${result.show}")
    result
  }

  private def isIndicesWriteAction = ProcessingContext.create { (bc, _) =>
    val result = indicesWriteAction.`match`(bc.requestContext.action)
    given BlockContext = bc
    logger.debug(s"Is indices write action? ${result.show}")
    result
  }

  private def kibanaCannotBeModified = !kibanaCanBeModified

  private def kibanaCanBeModified = ProcessingContext.create { (bc, _) =>
    val result = settings.access match {
      case RO | ROStrict | ApiOnly => false
      case RW | Admin | Unrestricted => true
    }
    given BlockContext = bc
    logger.debug(s"Can Kibana be modified? ${result.show}")
    result
  }

}

object BaseKibanaRule {

  abstract class Settings(val access: KibanaAccess,
                          val rorIndex: RorSettingsIndex)

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

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
import cats.implicits._
import eu.timepit.refined.auto._
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.BaseKibanaRule.Settings
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Local
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Local.devNullKibana
import tech.beshu.ror.accesscontrol.domain.IndexName.Wildcard
import tech.beshu.ror.accesscontrol.domain.KibanaAccess._
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.matchers.{IndicesMatcher, MatcherWithWildcardsScalaAdapter}
import tech.beshu.ror.accesscontrol.request.RequestContext

import java.util.regex.Pattern
import scala.util.Try

abstract class BaseKibanaRule(val settings: Settings) extends Logging {
  this: Rule =>

  import BaseKibanaRule._

  protected def shouldMatch: ProcessingContext = {
    // todo: not logged user + current metadata request = not matched rule
    isUnrestrictedAccessConfigured ||
      isCurrentUserMetadataRequest ||
      isDevNullKibanaRelated ||
      isRoAction ||
      isClusterAction ||
      emptyIndicesMatch ||
      isKibanaSimpleData ||
      isRoNonStrictCase ||
      isAdminAccessEligible ||
      isKibanaIndexRequest
  }

  private def isUnrestrictedAccessConfigured = ProcessingContext.create { (r, _) =>
    val result = settings.access === KibanaAccess.Unrestricted
    logger.debug(s"[${r.id.show}] Is unrestricted access configured? $result")
    result
  }

  private def isCurrentUserMetadataRequest = ProcessingContext.create { (r, _) =>
    val result = r.uriPath.isCurrentUserMetadataPath
    logger.debug(s"[${r.id.show}] Is is a current user metadata request? $result")
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

  private def isAdminAccessConfigured = ProcessingContext.create { (r, _) =>
    val result = settings.access === KibanaAccess.Admin
    logger.debug(s"[${r.id.show}] Is the admin access configured in the rule? $result")
    result
  }

  private def isRequestAllowedForAdminAccess = {
    doesRequestContainNoIndices ||
      isRequestRelatedToRorIndex ||
      isRequestRelatedToIndexManagementPath ||
      isRequestRelatedToTagsPath
  }

  private def doesRequestContainNoIndices = ProcessingContext.create { (r, _) =>
    val result = r.initialBlockContext.indices.isEmpty
    logger.debug(s"[${r.id.show}] Does request contain no indices? $result")
    result
  }

  private def isRoNonStrictCase = {
    isTargetingKibana &&
      isAccessOtherThanRoStrictConfigured &&
      kibanaCannotBeModified &&
      isNonStrictAllowedPath &&
      isNonStrictAction
  }

  private def isAccessOtherThanRoStrictConfigured = ProcessingContext.create { (r, _) =>
    val result = settings.access =!= ROStrict
    logger.debug(s"[${r.id.show}] Is access other than ROStrict configured? $result")
    result
  }

  private def isKibanaSimpleData = {
    kibanaCanBeModified && isRelatedToKibanaSampleDataIndex
  }

  private def emptyIndicesMatch = {
    doesRequestContainNoIndices && {
      (kibanaCanBeModified && isRwAction) ||
        (isAdminAccessConfigured && isAdminAction)
    }
  }

  // Save UI state in discover & Short urls
  private def isNonStrictAllowedPath = ProcessingContext.create { (requestContext, kibanaIndexName) =>
    val uriPath = requestContext.uriPath
    val nonStrictAllowedPaths = Try(Pattern.compile(
      "^/@kibana_index/(url|config/.*/_create|index-pattern|doc/index-pattern.*|doc/url.*)/.*|^/_template/.*|^/@kibana_index/doc/telemetry.*|^/@kibana_index/(_update/index-pattern.*|_update/url.*)|^/@kibana_index/_create/(url:.*)"
        .replace("@kibana_index", kibanaIndexName.stringify)
    )).toOption
    val result = nonStrictAllowedPaths match {
      case Some(paths) => paths.matcher(uriPath.value.value).find()
      case None => false
    }
    logger.debug(s"[${requestContext.id.show}] Is non strict allowed path? $result")
    result
  }

  private def isTargetingKibana = ProcessingContext.create { (requestContext, kibanaIndexName) =>
    val result = isRelatedToSingleIndex(kibanaIndexName)(requestContext, kibanaIndexName)
    logger.debug(s"[${requestContext.id.show}] Is targeting Kibana? $result")
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

  private def isRequestRelatedToTagsPath(pathPart: String) = ProcessingContext.create { (requestContext, _) =>
    val result = requestContext
      .headers
      .find(_.name === Header.Name.kibanaRequestPath)
      .exists(_.value.value.contains(s"/$pathPart/"))
    logger.debug(s"[${requestContext.id.show}] Does kibana request contains '$pathPart' in path? $result")
    result
  }

  // Allow other actions if devnull is targeted to readers and writers
  private def isDevNullKibanaRelated = {
    isRelatedToSingleIndex(devNullKibana)
  }

  private def isRelatedToSingleIndex(index: ClusterIndexName) = ProcessingContext.create { (requestContext, _) =>
    val result = requestContext.initialBlockContext.indices == Set(index)
    logger.debug(s"[${requestContext.id.show}] Is related to single index '${index.nonEmptyStringify}'? $result")
    result
  }

  private def isRelatedToKibanaSampleDataIndex = ProcessingContext.create { (requestContext, _) =>
    val result = requestContext.initialBlockContext.indices.toList match {
      case Nil => false
      case head :: Nil => Matchers.kibanaSampleDataIndexMatcher.`match`(head)
      case _ => false
    }
    logger.debug(s"[${requestContext.id.show}] Is related to Kibana sample data index? $result")
    result
  }

  private def isRoAction = ProcessingContext.create { (requestContext, _) =>
    val result = Matchers.roMatcher.`match`(requestContext.action)
    logger.debug(s"[${requestContext.id.show}] Is RO action? $result")
    result
  }

  private def isClusterAction = ProcessingContext.create { (requestContext, _) =>
    val result = Matchers.clusterMatcher.`match`(requestContext.action)
    logger.debug(s"[${requestContext.id.show}] Is Cluster action? $result")
    result
  }

  private def isRwAction = ProcessingContext.create { (requestContext, _) =>
    val result = Matchers.rwMatcher.`match`(requestContext.action)
    logger.debug(s"[${requestContext.id.show}] Is RW action? $result")
    result
  }

  private def isAdminAction = ProcessingContext.create { (requestContext, _) =>
    val result = Matchers.adminMatcher.`match`(requestContext.action)
    logger.debug(s"[${requestContext.id.show}] Is Admin action? $result")
    result
  }

  private def isNonStrictAction = ProcessingContext.create { (requestContext, _) =>
    val result = Matchers.nonStrictActions.`match`(requestContext.action)
    logger.debug(s"[${requestContext.id.show}] Is non strict action? $result")
    result
  }

  private def isIndicesWriteAction = ProcessingContext.create { (requestContext, _) =>
    val result = Matchers.indicesWriteAction.`match`(requestContext.action)
    logger.debug(s"[${requestContext.id.show}] Is indices write action? $result")
    result
  }

  private def kibanaCannotBeModified = !kibanaCanBeModified

  private def kibanaCanBeModified = ProcessingContext.create { (r, _) =>
    val result = settings.access match {
      case RO | ROStrict => false
      case RW | Admin | Unrestricted => true
    }
    logger.debug(s"[${r.id.show}] Can Kibana be modified? $result")
    result
  }

}

object BaseKibanaRule {

  abstract class Settings(val access: KibanaAccess,
                          val rorIndex: RorConfigurationIndex)
  private object Matchers {
    val roMatcher = MatcherWithWildcardsScalaAdapter.fromJavaSetString[Action](Constants.RO_ACTIONS)
    val rwMatcher = MatcherWithWildcardsScalaAdapter.fromJavaSetString[Action](Constants.RW_ACTIONS)
    val adminMatcher = MatcherWithWildcardsScalaAdapter.fromJavaSetString[Action](Constants.ADMIN_ACTIONS)
    val clusterMatcher = MatcherWithWildcardsScalaAdapter.fromJavaSetString[Action](Constants.CLUSTER_ACTIONS)
    val nonStrictActions = MatcherWithWildcardsScalaAdapter[Action](Set(
      Action("indices:data/write/*"), Action("indices:admin/template/put")
    ))
    val indicesWriteAction = MatcherWithWildcardsScalaAdapter[Action](Set(Action("indices:data/write/*")))

    val kibanaSampleDataIndexMatcher = IndicesMatcher.create[ClusterIndexName](Set(Local(Wildcard("kibana_sample_data_*"))))
  }

  type ProcessingContext = ReaderT[Id, (RequestContext, IndexName.Kibana), Boolean]
  object ProcessingContext {
    def create(func: (RequestContext, IndexName.Kibana) => Boolean): ProcessingContext =
      ReaderT[Id, (RequestContext, IndexName.Kibana), Boolean] { case (r, i) => func(r, i) }
  }

  implicit class ProcessingContextBooleanOps(val context1: ProcessingContext) extends AnyVal {
    def &&(context2: ProcessingContext): ProcessingContext =
      ProcessingContext.create { case (requestContext, kibanaIndexName) =>
        context1(requestContext, kibanaIndexName) && context2(requestContext, kibanaIndexName)
      }

    def ||(context2: ProcessingContext): ProcessingContext =
      ProcessingContext.create { case (requestContext, kibanaIndexName) =>
        context1(requestContext, kibanaIndexName) || context2(requestContext, kibanaIndexName)
      }

    def unary_! : ProcessingContext =
      ProcessingContext.create { case (requestContext, kibanaIndexName) =>
        !context1(requestContext, kibanaIndexName)
      }
  }
}

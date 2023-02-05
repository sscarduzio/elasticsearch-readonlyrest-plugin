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

import cats.implicits._
import eu.timepit.refined.auto._
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

abstract class BaseKibanaRule(val settings: Settings) {
  this: Rule =>

  import BaseKibanaRule._

  protected def shouldMatch: ProcessingContext[Boolean] = {
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

  private def isUnrestrictedAccessConfigured: ProcessingContext[Boolean] = { (_, _) =>
    settings.access === KibanaAccess.Unrestricted
  }

  private def isCurrentUserMetadataRequest: ProcessingContext[Boolean] = { (requestContext, _) =>
    requestContext.uriPath.isCurrentUserMetadataPath
  }

  private def isKibanaIndexRequest: ProcessingContext[Boolean] = {
    kibanaCanBeModified &&
      isTargetingKibana &&
      (isRoAction || isRwAction || isIndicesWriteAction)
  }

  private def isAdminAccessEligible: ProcessingContext[Boolean] = {
    isAdminAccessConfigured && isAdminAction && isRequestAllowedForAdminAccess
  }

  private def isAdminAccessConfigured: ProcessingContext[Boolean] = { (_, _) =>
    settings.access === KibanaAccess.Admin
  }

  private def isRequestAllowedForAdminAccess: ProcessingContext[Boolean] = {
    doesRequestContainNoIndices ||
      isRequestRelatedToRorIndex ||
      isRequestRelatedToIndexManagementPath ||
      isRequestRelatedToTagsPath
  }

  private def doesRequestContainNoIndices: ProcessingContext[Boolean] = { (requestContext, _) =>
    requestContext.initialBlockContext.indices.isEmpty
  }

  private def isRoNonStrictCase: ProcessingContext[Boolean] = {
    isTargetingKibana &&
      isAccessOtherThanRoStrictConfigured &&
      kibanaCannotBeModified &&
      isNonStrictAllowedPath &&
      isNonStrictAction
  }

  private def isAccessOtherThanRoStrictConfigured: ProcessingContext[Boolean] = { (_, _) =>
    settings.access =!= ROStrict
  }

  private def isKibanaSimpleData: ProcessingContext[Boolean] = {
    kibanaCanBeModified && isRelatedToKibanaSampleDataIndex
  }

  private def emptyIndicesMatch: ProcessingContext[Boolean] = {
    doesRequestContainNoIndices && {
      (kibanaCanBeModified && isRwAction) ||
        (isAdminAccessConfigured && isAdminAction)
    }
  }

  // Save UI state in discover & Short urls
  private def isNonStrictAllowedPath: ProcessingContext[Boolean] = { (requestContext, kibanaIndexName) =>
    val uriPath = currentUriPath(requestContext, kibanaIndexName)
    val nonStrictAllowedPaths = Try(Pattern.compile(
      "^/@kibana_index/(url|config/.*/_create|index-pattern|doc/index-pattern.*|doc/url.*)/.*|^/_template/.*|^/@kibana_index/doc/telemetry.*|^/@kibana_index/(_update/index-pattern.*|_update/url.*)|^/@kibana_index/_create/(url:.*)"
        .replace("@kibana_index", kibanaIndexName.stringify)
    )).toOption
    nonStrictAllowedPaths match {
      case Some(paths) => paths.matcher(uriPath.value.value).find()
      case None => false
    }
  }

  private def currentUriPath: ProcessingContext[UriPath] = { (requestContext, _) =>
    requestContext.uriPath
  }

  private def isTargetingKibana: ProcessingContext[Boolean] = { (requestContext, kibanaIndexName) =>
    isRelatedToSingleIndex(kibanaIndexName)(requestContext, kibanaIndexName)
  }

  private def isRequestRelatedToRorIndex: ProcessingContext[Boolean] = {
    isRelatedToSingleIndex(settings.rorIndex.toLocal)
  }

  private def isRequestRelatedToIndexManagementPath: ProcessingContext[Boolean] = {
    isRequestRelatedToTagsPath("index_management")
  }

  private def isRequestRelatedToTagsPath: ProcessingContext[Boolean] = {
    isRequestRelatedToTagsPath("tags")
  }

  private def isRequestRelatedToTagsPath(pathPart: String): ProcessingContext[Boolean] = { (requestContext, _) =>
    requestContext
      .headers
      .find(_.name === Header.Name.kibanaRequestPath)
      .exists(_.value.value.contains(s"/$pathPart/"))
  }

  // Allow other actions if devnull is targeted to readers and writers
  private def isDevNullKibanaRelated: ProcessingContext[Boolean] = {
    isRelatedToSingleIndex(devNullKibana)
  }

  private def isRelatedToSingleIndex(index: ClusterIndexName): ProcessingContext[Boolean] = { (requestContext, _) =>
    requestContext.initialBlockContext.indices == Set(index)
  }

  private def isRelatedToKibanaSampleDataIndex: ProcessingContext[Boolean] = { (requestContext, _) =>
    requestContext.initialBlockContext.indices.toList match {
      case Nil => false
      case head :: Nil => Matchers.kibanaSampleDataIndexMatcher.`match`(head)
      case _ => false
    }
  }

  private def isRoAction: ProcessingContext[Boolean] = { (requestContext, _) =>
    Matchers.roMatcher.`match`(requestContext.action)
  }

  private def isClusterAction: ProcessingContext[Boolean] = { (requestContext, _) =>
    Matchers.clusterMatcher.`match`(requestContext.action)
  }

  private def isRwAction: ProcessingContext[Boolean] = { (requestContext, _) =>
    Matchers.rwMatcher.`match`(requestContext.action)
  }

  private def isAdminAction: ProcessingContext[Boolean] = { (requestContext, _) =>
    Matchers.adminMatcher.`match`(requestContext.action)
  }

  private def isNonStrictAction: ProcessingContext[Boolean] = { (requestContext, _) =>
    Matchers.nonStrictActions.`match`(requestContext.action)
  }

  private def isIndicesWriteAction: ProcessingContext[Boolean] = { (requestContext, _) =>
    Matchers.indicesWriteAction.`match`(requestContext.action)
  }

  private def kibanaCannotBeModified = !kibanaCanBeModified

  private def kibanaCanBeModified: ProcessingContext[Boolean] = { (_, _) =>
    settings.access match {
      case RO | ROStrict => false
      case RW | Admin | Unrestricted => true
    }
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

  type ProcessingContext[T] = (RequestContext, IndexName.Kibana) => T
  implicit class ProcessingContextBooleanOps(val context1: ProcessingContext[Boolean]) extends AnyVal {
    def &&(context2: ProcessingContext[Boolean]): ProcessingContext[Boolean] = { case (requestContext, kibanaIndexName) =>
      context1(requestContext, kibanaIndexName) && context2(requestContext, kibanaIndexName)
    }

    def ||(context2: ProcessingContext[Boolean]): ProcessingContext[Boolean] = { case (requestContext, kibanaIndexName) =>
      context1(requestContext, kibanaIndexName) || context2(requestContext, kibanaIndexName)
    }

    def unary_! : ProcessingContext[Boolean] = { case (requestContext, kibanaIndexName) =>
      !context1(requestContext, kibanaIndexName)
    }
  }
}

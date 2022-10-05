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
package tech.beshu.ror.accesscontrol.blocks.rules

import cats.implicits._
import eu.timepit.refined.auto._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.blocks.rules.KibanaAccessRule._
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.{RegularRule, RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Local
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Local.devNullKibana
import tech.beshu.ror.accesscontrol.domain.IndexName.Wildcard
import tech.beshu.ror.accesscontrol.domain.KibanaAccess._
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.matchers.{IndicesMatcher, MatcherWithWildcardsScalaAdapter}

import java.util.regex.Pattern
import scala.util.Try

class KibanaAccessRule(val settings: Settings)
  extends RegularRule with Logging {

  override val name: Rule.Name = KibanaAccessRule.Name.name

  override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    if(shouldMatch(blockContext)) matched(blockContext)
    else Rejected[B]()
  }

  private def shouldMatch: ProcessingContext[Boolean] = {
    isUnrestrictedAccessConfigured ||
      isCurrentUserMetadataRequest ||
      isDevNullKibanaRelated ||
      isDevNullKibanaRelated ||
      isRoAction ||
      isClusterAction ||
      emptyIndicesMatch ||
      isKibanaSimplaData ||
      isRoNonStrictCase ||
      isAdminAccessEligible ||
      isKibanaIndexRequest
  }

  private def isUnrestrictedAccessConfigured: ProcessingContext[Boolean] = { _ =>
    settings.access === KibanaAccess.Unrestricted
  }

  private def isCurrentUserMetadataRequest: ProcessingContext[Boolean] = { blockContext =>
    blockContext.requestContext.uriPath.isCurrentUserMetadataPath
  }

  private def isKibanaIndexRequest: ProcessingContext[Boolean] = {
    kibanaCanBeModified &&
      isTargetingKibana &&
      (isRoAction || isRwAction || isIndicesWriteAction)
  }

  private def currentKibanaIndex: ProcessingContext[ClusterIndexName] = { blockContext =>
    blockContext.userMetadata.kibanaIndex.getOrElse(ClusterIndexName.Local.kibana)
  }

  private def isAdminAccessEligible: ProcessingContext[Boolean] = {
    isAdminAccessConfigured && isAdminAction && isRequestAllowedForAdminAccess
  }

  private def isAdminAccessConfigured: ProcessingContext[Boolean] = {
    _ => settings.access === KibanaAccess.Admin
  }

  private def isRequestAllowedForAdminAccess: ProcessingContext[Boolean] = {
    doesRequestContainNoIndices ||
      isRequestRelatedToRorIndex ||
      isRequestRelatedToIndexManagementPath
  }

  private def doesRequestContainNoIndices: ProcessingContext[Boolean] = { blockContext =>
    blockContext.requestContext.initialBlockContext.indices.isEmpty
  }

  private def isRoNonStrictCase: ProcessingContext[Boolean] = {
    isTargetingKibana &&
      isAccessOtherThanRoStrictConfigured &&
      kibanaCannotBeModified &&
      isNonStrictAllowedPath &&
      isNonStrictAction
  }

  private def isAccessOtherThanRoStrictConfigured: ProcessingContext[Boolean] = { _ =>
    settings.access =!= ROStrict
  }

  private def isKibanaSimplaData: ProcessingContext[Boolean] = {
    kibanaCanBeModified && isRelatedToKibanaSampleDataIndex
  }

  private def emptyIndicesMatch: ProcessingContext[Boolean] = {
    doesRequestContainNoIndices && {
      (kibanaCanBeModified && isRwAction) ||
        (isAdminAccessConfigured && isAdminAction)
    }
  }

  // Save UI state in discover & Short urls
  private def isNonStrictAllowedPath: ProcessingContext[Boolean] = {
    for {
      kibanaIndex <- currentKibanaIndex
      uriPath <- currentUriPath
    } yield {
      val nonStrictAllowedPaths = Try(Pattern.compile(
        "^/@kibana_index/(url|config/.*/_create|index-pattern|doc/index-pattern.*|doc/url.*)/.*|^/_template/.*|^/@kibana_index/doc/telemetry.*|^/@kibana_index/(_update/index-pattern.*|_update/url.*)|^/@kibana_index/_create/(url:.*)"
          .replace("@kibana_index", kibanaIndex.stringify)
      )).toOption
      nonStrictAllowedPaths match {
        case Some(paths) => paths.matcher(uriPath.value.value).find()
        case None => false
      }
    }
  }

  private def currentUriPath: ProcessingContext[UriPath] = { blockContext =>
    blockContext.requestContext.uriPath
  }

  private def isTargetingKibana: ProcessingContext[Boolean] = {
    for {
      kibanaIndex <- currentKibanaIndex
      result <- isRelatedToSingleIndex(kibanaIndex)
    } yield result
  }

  private def isRequestRelatedToRorIndex: ProcessingContext[Boolean] = {
    isRelatedToSingleIndex(settings.rorIndex.toLocal)
  }

  private def isRequestRelatedToIndexManagementPath: ProcessingContext[Boolean] = { blockContext =>
    blockContext
      .requestContext.headers
      .find(_.name === Header.Name.kibanaRequestPath)
      .exists(_.value.value.contains("/index_management/"))
  }

  // Allow other actions if devnull is targeted to readers and writers
  private def isDevNullKibanaRelated: ProcessingContext[Boolean] = {
    isRelatedToSingleIndex(devNullKibana)
  }

  private def isRelatedToSingleIndex(index: ClusterIndexName): ProcessingContext[Boolean] = { blockContext =>
    blockContext.requestContext.initialBlockContext.indices == Set(index)
  }

  private def isRelatedToKibanaSampleDataIndex: ProcessingContext[Boolean] = { blockContext =>
    blockContext.requestContext.initialBlockContext.indices.toList match {
      case Nil => false
      case head :: Nil => Matchers.kibanaSampleDataIndexMatcher.`match`(head)
      case _ => false
    }
  }

  private def isRoAction: ProcessingContext[Boolean] = { blockContext =>
    Matchers.roMatcher.`match`(blockContext.requestContext.action)
  }

  private def isClusterAction: ProcessingContext[Boolean] = { blockContext =>
    Matchers.clusterMatcher.`match`(blockContext.requestContext.action)
  }

  private def isRwAction: ProcessingContext[Boolean] = { blockContext =>
    Matchers.rwMatcher.`match`(blockContext.requestContext.action)
  }

  private def isAdminAction: ProcessingContext[Boolean] = { blockContext =>
    Matchers.adminMatcher.`match`(blockContext.requestContext.action)
  }

  private def isNonStrictAction: ProcessingContext[Boolean] = { blockContext =>
    Matchers.nonStrictActions.`match`(blockContext.requestContext.action)
  }

  private def isIndicesWriteAction: ProcessingContext[Boolean] = { blockContext =>
    Matchers.indicesWriteAction.`match`(blockContext.requestContext.action)
  }

  private def kibanaCannotBeModified = !kibanaCanBeModified

  private def kibanaCanBeModified: ProcessingContext[Boolean] = { _ =>
    settings.access match {
      case RO | ROStrict => false
      case RW | Admin | Unrestricted => true
    }
  }

  private def matched[B <: BlockContext : BlockContextUpdater](blockContext: B): Fulfilled[B] = {
    val kibanaIndex = currentKibanaIndex(blockContext)
    RuleResult.Fulfilled[B] {
      blockContext.withUserMetadata(_
        .withKibanaAccess(settings.access)
        .withKibanaIndex(kibanaIndex)
      )
    }
  }
}

object KibanaAccessRule {

  implicit case object Name extends RuleName[KibanaAccessRule] {
    override val name = Rule.Name("kibana_access")
  }

  final case class Settings(access: KibanaAccess,
                            rorIndex: RorConfigurationIndex)
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

  type ProcessingContext[T] = BlockContext => T
  implicit class ProcessingContextBooleanOps(val context1: ProcessingContext[Boolean]) extends AnyVal {
    def &&(context2: ProcessingContext[Boolean]): ProcessingContext[Boolean] = { blockContext =>
      context1(blockContext) && context2(blockContext)
    }

    def ||(context2: ProcessingContext[Boolean]): ProcessingContext[Boolean] = { blockContext =>
      context1(blockContext) || context2(blockContext)
    }

    def unary_! : ProcessingContext[Boolean] = { blockContext =>
      !context1(blockContext)
    }
  }
}

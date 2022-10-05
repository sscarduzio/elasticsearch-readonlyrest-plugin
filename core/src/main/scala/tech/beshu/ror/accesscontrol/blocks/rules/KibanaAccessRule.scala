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
    val requestContext = blockContext.requestContext

    if (settings.access === KibanaAccess.Unrestricted)
      Fulfilled(modifyMatched(blockContext))
    else if (requestContext.uriPath.isCurrentUserMetadataPath)
      Fulfilled(modifyMatched(blockContext))
    else if (isDevNullKibanaRelated(blockContext))
      Fulfilled(modifyMatched(blockContext))
    else if (isRoAction(blockContext)) // Any index, read op
      Fulfilled(modifyMatched(blockContext))
    else if (isClusterAction(blockContext))
      Fulfilled(modifyMatched(blockContext))
    else if (emptyIndicesMatch(blockContext))
      Fulfilled(modifyMatched(blockContext))
    else if (isKibanaSimplaData(blockContext))
      Fulfilled(modifyMatched(blockContext))
    else if (isRoNonStrictCase(blockContext)) {
      Fulfilled(modifyMatched(blockContext, Some(kibanaIndexFrom(blockContext))))
    } else if (isAdminAccessEligible(blockContext)) {
      Fulfilled(modifyMatched(blockContext, Some(kibanaIndexFrom(blockContext))))
    } else if (isKibanaIndexRequest(blockContext)) {
      Fulfilled(modifyMatched(blockContext, Some(kibanaIndexFrom(blockContext))))
    } else {
      Rejected()
    }
  }

  private def isKibanaIndexRequest(blockContext: BlockContext) = {
    kibanaCanBeModified && isTargetingKibana(blockContext) && (
      isRoAction(blockContext) || isRwAction(blockContext) || isIndicesWriteAction(blockContext)
    )
  }

  private def kibanaIndexFrom(blockContext: BlockContext) = {
    blockContext.userMetadata.kibanaIndex.getOrElse(ClusterIndexName.Local.kibana)
  }

  private def isAdminAccessEligible(blockContext: BlockContext) = {
    settings.access === KibanaAccess.Admin &&
      isAdminAction(blockContext) &&
      isRequestAllowedForAdminAccess(blockContext)
  }
  
  private def isRequestAllowedForAdminAccess(blockContext: BlockContext) = {
    doesRequestContainNoIndices(blockContext) ||
      isRequestRelatedToRorIndex(blockContext) ||
      isRequestRelatedToIndexManagementPath(blockContext)
  }

  private def doesRequestContainNoIndices(blockContext: BlockContext) = {
    blockContext.requestContext.initialBlockContext.indices.isEmpty
  }

  private def isRoNonStrictCase(blockContext: BlockContext) = {
    isTargetingKibana(blockContext) &&
      settings.access =!= ROStrict &&
      !kibanaCanBeModified &&
      isNonStrictAllowedPath(blockContext) &&
      isNonStrictAction(blockContext)
  }

  private def isKibanaSimplaData(blockContext: BlockContext) = {
    kibanaCanBeModified && isRelatedToKibanaSampleDataIndex(blockContext)
  }

  private def emptyIndicesMatch(blockContext: BlockContext) = {
    doesRequestContainNoIndices(blockContext) && {
      (kibanaCanBeModified && isRwAction(blockContext)) ||
        (settings.access === KibanaAccess.Admin && isAdminAction(blockContext))
    }
  }

  // Save UI state in discover & Short urls
  private def isNonStrictAllowedPath(blockContext: BlockContext) = {
    val kibanaIndex = kibanaIndexFrom(blockContext)
    val nonStrictAllowedPaths = Try(Pattern.compile(
      "^/@kibana_index/(url|config/.*/_create|index-pattern|doc/index-pattern.*|doc/url.*)/.*|^/_template/.*|^/@kibana_index/doc/telemetry.*|^/@kibana_index/(_update/index-pattern.*|_update/url.*)|^/@kibana_index/_create/(url:.*)"
        .replace("@kibana_index", kibanaIndex.stringify)
    )).toOption
    nonStrictAllowedPaths match {
      case Some(paths) => paths.matcher(blockContext.requestContext.uriPath.value.value).find()
      case None => false
    }
  }

  private def isTargetingKibana(blockContext: BlockContext) = {
    isRelatedToSingleIndex(blockContext, kibanaIndexFrom(blockContext))
  }

  private def isRequestRelatedToRorIndex(blockContext: BlockContext) = {
    isRelatedToSingleIndex(blockContext, settings.rorIndex.toLocal)
  }

  private def isRequestRelatedToIndexManagementPath(blockContext: BlockContext) = {
    blockContext
      .requestContext.headers
      .find(_.name === Header.Name.kibanaRequestPath)
      .exists(_.value.value.contains("/index_management/"))
  }

  // Allow other actions if devnull is targeted to readers and writers
  private def isDevNullKibanaRelated(blockContext: BlockContext) = {
    isRelatedToSingleIndex(blockContext, devNullKibana)
  }

  private def isRelatedToSingleIndex(blockContext: BlockContext,
                                     index: ClusterIndexName) = {
    blockContext.requestContext.initialBlockContext.indices == Set(index)
  }

  private def isRelatedToKibanaSampleDataIndex(blockContext: BlockContext) = {
    blockContext.requestContext.initialBlockContext.indices.toList match {
      case Nil => false
      case head :: Nil => Matchers.kibanaSampleDataIndexMatcher.`match`(head)
      case _ => false
    }
  }

  private def isRoAction(blockContext: BlockContext) = {
    Matchers.roMatcher.`match`(blockContext.requestContext.action)
  }

  private def isClusterAction(blockContext: BlockContext) = {
    Matchers.clusterMatcher.`match`(blockContext.requestContext.action)
  }

  private def isRwAction(blockContext: BlockContext) = {
    Matchers.rwMatcher.`match`(blockContext.requestContext.action)
  }

  private def isAdminAction(blockContext: BlockContext) = {
    Matchers.adminMatcher.`match`(blockContext.requestContext.action)
  }

  private def isNonStrictAction(blockContext: BlockContext) = {
    Matchers.nonStrictActions.`match`(blockContext.requestContext.action)
  }

  private def isIndicesWriteAction(blockContext: BlockContext) = {
    Matchers.indicesWriteAction.`match`(blockContext.requestContext.action)
  }

  private def modifyMatched[B <: BlockContext : BlockContextUpdater](blockContext: B, kibanaIndex: Option[ClusterIndexName] = None) = {
    def applyKibanaAccess = (bc: B) => {
      bc.withUserMetadata(_.withKibanaAccess(settings.access))
    }

    def applyKibanaIndex = (bc: B) => {
      kibanaIndex match {
        case Some(index) => bc.withUserMetadata(_.withKibanaIndex(index))
        case None => bc
      }
    }

    (applyKibanaAccess :: applyKibanaIndex :: Nil).reduceLeft(_ andThen _).apply(blockContext)
  }

  private val kibanaCanBeModified: Boolean = settings.access match {
    case RO | ROStrict => false
    case RW | Admin | Unrestricted => true
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
}

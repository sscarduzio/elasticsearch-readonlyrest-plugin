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

import java.util.regex.Pattern

import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.Constants
import tech.beshu.ror.accesscontrol.blocks.rules.KibanaAccessRule._
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.ClusterIndexName.Local.devNullKibana
import tech.beshu.ror.accesscontrol.domain.KibanaAccess._
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.matchers.MatcherWithWildcardsScalaAdapter
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.show.logs._

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
    // Allow other actions if devnull is targeted to readers and writers
    else if (blockContext.requestContext.initialBlockContext.indices.contains(devNullKibana))
      Fulfilled(modifyMatched(blockContext))
    // Any index, read op
    else if (Matchers.roMatcher.`match`(requestContext.action))
      Fulfilled(modifyMatched(blockContext))
    else if (Matchers.clusterMatcher.`match`(requestContext.action))
      Fulfilled(modifyMatched(blockContext))
    else if (emptyIndicesMatch(requestContext))
      Fulfilled(modifyMatched(blockContext))
    else if (isKibanaSimplaData(requestContext))
      Fulfilled(modifyMatched(blockContext))
    else
      processCheck(blockContext)
  }

  private def processCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): RuleResult[B] = {
    val kibanaIndex = determineKibanaIndex(blockContext)
    // Save UI state in discover & Short urls
    kibanaIndexPattern(kibanaIndex) match {
      case None =>
        Rejected()
      case Some(pattern) if isRoNonStrictCase(blockContext.requestContext, kibanaIndex, pattern) =>
        Fulfilled(modifyMatched(blockContext, Some(kibanaIndex)))
      case Some(_) =>
        continueProcessing(blockContext, kibanaIndex)
    }
  }

  private def determineKibanaIndex(blockContext: BlockContext) = {
    blockContext.userMetadata.kibanaIndex.getOrElse(ClusterIndexName.Local.kibana)
  }

  private def continueProcessing[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                          kibanaIndex: ClusterIndexName): RuleResult[B] = {
    val requestContext = blockContext.requestContext
    if (kibanaCanBeModified && isTargetingKibana(requestContext, kibanaIndex)) {
      if (Matchers.roMatcher.`match`(requestContext.action) ||
        Matchers.rwMatcher.`match`(requestContext.action) ||
        requestContext.action.hasPrefix("indices:data/write")) {
        logger.debug(s"RW access to Kibana index: ${requestContext.id.show}")
        Fulfilled(modifyMatched(blockContext, Some(kibanaIndex)))
      } else {
        logger.info(s"RW access to Kibana, but unrecognized action ${requestContext.action.show} reqID: ${requestContext.id.show}")
        Rejected()
      }
    } else if (isReadonlyrestAdmin(requestContext)) {
      Fulfilled(modifyMatched(blockContext, Some(kibanaIndex)))
    } else {
      logger.debug(s"KIBANA ACCESS DENIED ${requestContext.id.show}")
      Rejected()
    }
  }

  private def isReadonlyrestAdmin(requestContext: RequestContext) = {
    val originRequestIndices = requestContext.initialBlockContext.indices.map {
      case ClusterIndexName.Local(value) => value
      case ClusterIndexName.Remote(value, _) => value
    }
    (originRequestIndices.isEmpty || originRequestIndices.contains(settings.rorIndex.index)) &&
      settings.access === KibanaAccess.Admin &&
      Matchers.adminMatcher.`match`(requestContext.action)
  }

  private def isRoNonStrictCase(requestContext: RequestContext, kibanaIndex: ClusterIndexName, nonStrictAllowedPaths: Pattern) = {
    isTargetingKibana(requestContext, kibanaIndex) &&
      settings.access =!= ROStrict &&
      !kibanaCanBeModified &&
      nonStrictAllowedPaths.matcher(requestContext.uriPath.value.value).find() &&
      (requestContext.action.hasPrefix("indices:data/write/") || requestContext.action.hasPrefix("indices:admin/template/put"))
  }

  private def isKibanaSimplaData(requestContext: RequestContext) = {
    val originRequestIndices = requestContext.initialBlockContext.indices
    kibanaCanBeModified && originRequestIndices.size === 1 && originRequestIndices.head.hasPrefix("kibana_sample_data_")
  }

  private def emptyIndicesMatch(requestContext: RequestContext) = {
    val originRequestIndices = requestContext.initialBlockContext.indices
    originRequestIndices.isEmpty && {
      (kibanaCanBeModified && Matchers.rwMatcher.`match`(requestContext.action)) ||
        (settings.access === KibanaAccess.Admin && Matchers.adminMatcher.`match`(requestContext.action))
    }
  }

  private def isTargetingKibana(requestContext: RequestContext, kibanaIndex: ClusterIndexName) = {
    requestContext.initialBlockContext.indices.toList match {
      case head :: Nil => head === kibanaIndex
      case _ => false
    }
  }

  private def kibanaIndexPattern(kibanaIndex: ClusterIndexName) = {
    Try(Pattern.compile(
      "^/@kibana_index/(url|config/.*/_create|index-pattern|doc/index-pattern.*|doc/url.*)/.*|^/_template/.*|^/@kibana_index/doc/telemetry.*|^/@kibana_index/(_update/index-pattern.*|_update/url.*)|^/@kibana_index/_create/(url:.*)"
        .replace("@kibana_index", kibanaIndex.stringify)
    )).toOption
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
  }

}

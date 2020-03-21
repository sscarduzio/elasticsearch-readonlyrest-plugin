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
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.KibanaAccessRule._
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.utils.{MatcherWithWildcardsScalaAdapter, StringTNaturalTransformation}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeSingleResolvableVariable
import tech.beshu.ror.accesscontrol.domain.IndexName.devNullKibana
import tech.beshu.ror.accesscontrol.domain.KibanaAccess._
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.utils.MatcherWithWildcards

import scala.util.Try

class KibanaAccessRule(val settings: Settings)
  extends RegularRule with Logging {

  import KibanaAccessRule.stringActionNT

  override val name: Rule.Name = KibanaAccessRule.name

  override def check[T <: Operation](requestContext: RequestContext[T],
                                     blockContext: BlockContext[T]): Task[RuleResult[T]] = Task {
    if (requestContext.uriPath.isCurrentUserMetadataPath) Fulfilled(modifyMatched(blockContext))
    // Allow other actions if devnull is targeted to readers and writers
    else if (requestContext.indices.contains(devNullKibana)) Fulfilled(modifyMatched(blockContext))
    // Any index, read op
    else if (Matchers.roMatcher.`match`(requestContext.action)) Fulfilled(modifyMatched(blockContext))
    else if (Matchers.clusterMatcher.`match`(requestContext.action)) Fulfilled(modifyMatched(blockContext))
    else if (emptyIndicesMatch(requestContext)) Fulfilled(modifyMatched(blockContext))
    else if (isKibanaSimplaData(requestContext)) Fulfilled(modifyMatched(blockContext))
    else processCheck(requestContext, blockContext)
  }

  private def processCheck[T <: Operation](requestContext: RequestContext[T], blockContext: BlockContext[T]): RuleResult[T] = {
    val kibanaIndex = settings
      .kibanaIndex
      .resolve(requestContext, blockContext)
      .getOrElse(IndexName.kibana)

    // Save UI state in discover & Short urls
    kibanaIndexPattern(kibanaIndex) match {
      case None =>
        Rejected()
      case Some(pattern) if isRoNonStrictCase(requestContext, kibanaIndex, pattern) =>
        Fulfilled(modifyMatched(blockContext, Some(kibanaIndex)))
      case Some(_) =>
        continueProcessing(requestContext, blockContext, kibanaIndex)
    }
  }

  private def continueProcessing[T <: Operation](requestContext: RequestContext[T],
                                 blockContext: BlockContext[T],
                                 kibanaIndex: IndexName): RuleResult[T] = {
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

  private def isReadonlyrestAdmin[T <: Operation](requestContext: RequestContext[T]) = {
    (requestContext.indices.isEmpty || requestContext.indices.contains(settings.rorIndex)) &&
      settings.access === KibanaAccess.Admin &&
      Matchers.adminMatcher.`match`(requestContext.action)
  }

  private def isRoNonStrictCase[T <: Operation](requestContext: RequestContext[T], kibanaIndex: IndexName, nonStrictAllowedPaths: Pattern) = {
    isTargetingKibana(requestContext, kibanaIndex) &&
      settings.access =!= ROStrict &&
      !kibanaCanBeModified &&
      nonStrictAllowedPaths.matcher(requestContext.uriPath.value).find() &&
      (requestContext.action.hasPrefix("indices:data/write/") || requestContext.action.hasPrefix("indices:admin/template/put"))
  }

  private def isKibanaSimplaData[T <: Operation](requestContext: RequestContext[T]) = {
    kibanaCanBeModified && requestContext.indices.size == 1 && requestContext.indices.head.hasPrefix("kibana_sample_data_")
  }

  private def emptyIndicesMatch[T <: Operation](requestContext: RequestContext[T]) = {
    requestContext.indices.isEmpty && {
      (kibanaCanBeModified && Matchers.rwMatcher.`match`(requestContext.action)) ||
        (settings.access === KibanaAccess.Admin && Matchers.adminMatcher.`match`(requestContext.action))
    }
  }

  private def isTargetingKibana[T <: Operation](requestContext: RequestContext[T], kibanaIndex: IndexName) = {
    requestContext.indices.toList match {
      case head :: Nil => head === kibanaIndex
      case _ => false
    }
  }

  private def kibanaIndexPattern(kibanaIndex: IndexName) = {
    Try(Pattern.compile(
      "^/@kibana_index/(url|config/.*/_create|index-pattern|doc/index-pattern.*|doc/url.*)/.*|^/_template/.*|^/@kibana_index/doc/telemetry.*|^/@kibana_index/(_update/index-pattern.*|_update/url.*)"
        .replace("@kibana_index", kibanaIndex.value.value)
    )).toOption
  }

  private def modifyMatched[T <: Operation](blockContext: BlockContext[T], kibanaIndex: Option[IndexName] = None) = {
    def applyKibanaAccess = (bc: BlockContext[T]) => {
      bc.withKibanaAccess(settings.access)
    }

    def applyKibanaIndex = (bc: BlockContext[T]) => {
      kibanaIndex match {
        case Some(index) => bc.withKibanaIndex(index)
        case None => bc
      }
    }

    (applyKibanaAccess :: applyKibanaIndex :: Nil).reduceLeft(_ andThen _).apply(blockContext)
  }

  private val kibanaCanBeModified: Boolean = settings.access match {
    case RO | ROStrict => false
    case RW | Admin => true
  }
}

object KibanaAccessRule {
  val name = Rule.Name("kibana_access")

  final case class Settings(access: KibanaAccess,
                            kibanaIndex: RuntimeSingleResolvableVariable[IndexName],
                            rorIndex: IndexName)

  private object Matchers {
    val roMatcher = new MatcherWithWildcardsScalaAdapter(new MatcherWithWildcards(Constants.RO_ACTIONS))
    val rwMatcher = new MatcherWithWildcardsScalaAdapter(new MatcherWithWildcards(Constants.RW_ACTIONS))
    val adminMatcher = new MatcherWithWildcardsScalaAdapter(new MatcherWithWildcards(Constants.ADMIN_ACTIONS))
    val clusterMatcher = new MatcherWithWildcardsScalaAdapter(new MatcherWithWildcards(Constants.CLUSTER_ACTIONS))
  }

  private implicit val stringActionNT: StringTNaturalTransformation[Action] =
    StringTNaturalTransformation[Action](Action.apply, _.value)
}

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
package tech.beshu.ror.acl.blocks.rules

import cats.data.NonEmptySet
import cats.implicits._
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.ZeroKnowledgeIndexFilter
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.rules.IndicesRule.{CanPass, Settings}
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.acl.blocks.rules.utils.ZeroKnowledgeIndexFilterScalaAdapter.CheckResult
import tech.beshu.ror.acl.blocks.rules.utils.{Matcher, MatcherWithWildcardsScalaAdapter, StringTNaturalTransformation, ZeroKnowledgeIndexFilterScalaAdapter}
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.acl.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.acl.domain.Action.{mSearchAction, searchAction}
import tech.beshu.ror.acl.domain.IndexName
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.acl.request.RequestContext
import tech.beshu.ror.acl.utils.RuntimeMultiResolvableVariableOps.resolveAll
import tech.beshu.ror.utils.MatcherWithWildcards

import scala.collection.JavaConverters._
import scala.collection.SortedSet
import scala.language.postfixOps


class IndicesRule(val settings: Settings)
  extends RegularRule with Logging {

  import IndicesCheckContinuation._
  import IndicesRule.stringIndexNameNT

  override val name: Rule.Name = IndicesRule.name

  private val zKindexFilter = new ZeroKnowledgeIndexFilterScalaAdapter(new ZeroKnowledgeIndexFilter(true))

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task {
    if (!requestContext.involvesIndices) Fulfilled(blockContext)
    else if (matchAll) Fulfilled(blockContext)
    else process(requestContext, blockContext)
  }

  private def process(requestContext: RequestContext, blockContext: BlockContext): RuleResult = {
    val matcher: Matcher = initialMatcher.getOrElse {
      val resolvedIndices = resolveAll(settings.allowedIndices, requestContext, blockContext)
      new MatcherWithWildcardsScalaAdapter(new MatcherWithWildcards(resolvedIndices.map(_.value.value).toSet.asJava))
    }
    // Cross cluster search awareness
    if (isSearchAction(requestContext)) {

      val (crossClusterIndices, localIndices) =
        if (!requestContext.hasRemoteClusters) {
          // Only requested X-cluster when we don't have remote, will return empty.
          val crossClusterIndices = requestContext.indices.filter(_.isClusterIndex)
          if (requestContext.indices.nonEmpty && requestContext.indices.size == crossClusterIndices.size) {
            return Fulfilled(blockContext)
          }
          // If you requested local + X-cluster indices while we don't have remotes, it's like you asked for only local indices.
          (Set.empty[IndexName], requestContext.indices.filter(index => !index.isClusterIndex))
        } else {
          requestContext.indices.partition(_.isClusterIndex)
        }

      // Scatter gather for local and remote indices barring algorithms
      if (crossClusterIndices.nonEmpty) {
        // Run the local algorithm
        val processedLocalIndices =
          if (localIndices.isEmpty && crossClusterIndices.nonEmpty) {
            // Don't run locally if only have crossCluster, otherwise you'll resolve the equivalent of "*"
            localIndices
          } else {
            canPass(requestContext, matcher) match {
              case CanPass.No =>
                return Rejected
              case CanPass.Yes(indices) =>
                indices
            }
          }
        // Run the remote algorithm (without knowing the remote list of indices)
        val allProcessedIndices = zKindexFilter.check(crossClusterIndices, matcher) match {
          case CheckResult.Ok(processedIndices) => processedIndices ++ processedLocalIndices
          case CheckResult.Failed =>
            return Rejected
        }

        return Fulfilled(blockContextWithIndices(blockContext, allProcessedIndices))
      }
    }

    canPass(requestContext, matcher) match {
      case CanPass.Yes(indices) =>
        Fulfilled(blockContextWithIndices(blockContext, indices))
      case CanPass.No =>
        Rejected
    }
  }

  private def blockContextWithIndices(blockContext: BlockContext, indices: Set[IndexName]) = {
    NonEmptySet.fromSet(SortedSet.empty[IndexName] ++ indices) match {
      case Some(indexes) => blockContext.withIndices(indexes)
      case None => blockContext
    }
  }

  private def isSearchAction(requestContext: RequestContext): Boolean =
    requestContext.isReadOnlyRequest && List(searchAction, mSearchAction).contains(requestContext.action)

  private def canPass(requestContext: RequestContext, matcher: Matcher): CanPass = {
    if (requestContext.isReadOnlyRequest) canReadOnlyRequestPass(requestContext, matcher)
    else canWriteRequestPass(requestContext, matcher)
  }

  private def canReadOnlyRequestPass(requestContext: RequestContext, matcher: Matcher): CanPass = {
    val result = for {
      _ <- noneOrAllIndices(requestContext, matcher)
      _ <- allIndicesMatchedByWildcard(requestContext, matcher)
      _ <- atLeastOneNonWildcardIndexNotExist(requestContext, matcher)
      _ <- expandedIndices(requestContext, matcher)
      _ <- indicesAliases(requestContext, matcher)
    } yield ()
    result.left.getOrElse(CanPass.No)
  }

  private def noneOrAllIndices(requestContext: RequestContext, matcher: Matcher): IndicesCheckContinuation = {
    logger.debug("Checking - none or all indices ...")
    val indices = requestContext.indices
    if (indices.isEmpty || indices.contains(IndexName.all) || indices.contains(IndexName.wildcard)) {
      val allowedIdxs = matcher.filter(requestContext.allIndicesAndAliases.flatMap(_.all))
      stop(if (allowedIdxs.nonEmpty) CanPass.Yes(allowedIdxs) else CanPass.No)
    } else {
      continue
    }
  }

  private def allIndicesMatchedByWildcard(requestContext: RequestContext, matcher: Matcher): IndicesCheckContinuation = {
    logger.debug("Checking if all indices are matched ...")
    val indices = requestContext.indices
    indices.toList match {
      case index :: Nil =>
        if(matcher.`match`(index)) stop(CanPass.Yes(Set.empty))
        else continue
      case _ if matcher.filter(indices) === indices =>
        stop(CanPass.Yes(Set.empty))
      case _ =>
        continue
    }
  }

  private def atLeastOneNonWildcardIndexNotExist(requestContext: RequestContext, matcher: Matcher): IndicesCheckContinuation = {
    logger.debug("Checking if at least one non-wildcard index doesn't exist ...")
    val indices = requestContext.indices
    val real = requestContext.allIndicesAndAliases.flatMap(_.all)
    val nonExistent = indices.foldLeft(Set.empty[IndexName]) {
      case (acc, index) if !index.hasWildcard && !real.contains(index) => acc + index
      case (acc, _) => acc
    }
    if(nonExistent.nonEmpty && !requestContext.isCompositeRequest) {
      stop(CanPass.No)
    } else if(nonExistent.nonEmpty && (indices -- nonExistent).isEmpty) {
      stop(CanPass.No)
    } else {
      continue
    }
  }

  private def expandedIndices(requestContext: RequestContext, matcher: Matcher): IndicesCheckContinuation = {
    logger.debug("Checking - expanding wildcard indices ...")
    val expansion = expandedIndices(requestContext)
    if (expansion.isEmpty) {
      stop(CanPass.No)
    } else {
      val allowedExpansion = matcher.filter(expansion)
      if(allowedExpansion.nonEmpty) {
        stop(CanPass.Yes(allowedExpansion))
      } else {
        continue
      }
    }
  }

  private def indicesAliases(requestContext: RequestContext, matcher: Matcher): IndicesCheckContinuation = {
    logger.debug("Checking - indices aliases ...")
    val indices = requestContext.indices
    val indicesAndAliases = requestContext.allIndicesAndAliases
    val aliases = indicesAndAliases.flatMap(_.aliases)
    val requestAliases = new MatcherWithWildcardsScalaAdapter(new MatcherWithWildcards(indices.map(_.value.value).asJava))
      .filter(aliases)
    val realIndicesRelatedToRequestAliases =
      indicesAndAliases
        .filter(ia => requestAliases.intersect(ia.aliases).nonEmpty)
        .map(_.index)
    val allowedRealIndices = matcher.filter(realIndicesRelatedToRequestAliases)
    if(allowedRealIndices.nonEmpty) {
      stop(CanPass.Yes(allowedRealIndices))
    } else {
      continue
    }
  }

  private def canWriteRequestPass(requestContext: RequestContext, matcher: Matcher): CanPass = {
    logger.debug("Checking - write request ...")
    val indices = requestContext.indices
    // Write requests
    // Handle <no-index> (#TODO LEGACY)
    logger.debug("Stage 7")
    if (indices.isEmpty && matcher.contains("<no-index>")) {
      CanPass.Yes(Set.empty)
    } else {
      // Reject write if at least one requested index is not allowed by the rule conf
      logger.debug("Stage 8")
      indices.foldLeft(CanPass.Yes(Set.empty): CanPass) {
        case (CanPass.Yes(_), index) =>
          if (matcher.`match`(index)) CanPass.Yes(Set.empty)
          else CanPass.No
        case (CanPass.No, _) => CanPass.No

      }
    }
  }

  private def expandedIndices(requestContext: RequestContext): Set[IndexName] = {
    new MatcherWithWildcardsScalaAdapter(new MatcherWithWildcards(requestContext.indices.map(_.value.value).asJava))
      .filter(requestContext.allIndicesAndAliases.flatMap(_.all))
  }

  private val alreadyResolvedIndices =
    settings
      .allowedIndices
      .toList
      .collect { case AlreadyResolved(indices) => indices.toList }
      .flatten
      .toSet

  private val initialMatcher = {
    val hasVariables = settings.allowedIndices.exists {
      case AlreadyResolved(_) => false
      case _ => true
    }
    if (hasVariables) None
    else Some {
      new MatcherWithWildcardsScalaAdapter(
        new MatcherWithWildcards(alreadyResolvedIndices.map(_.value.value).asJava)
      )
    }
  }

  private val matchAll = settings.allowedIndices.exists {
    case AlreadyResolved(indices) if indices.contains_(IndexName.`wildcard`) => true
    case _ => false
  }

  private type IndicesCheckContinuation = Either[CanPass, Unit]
  private object IndicesCheckContinuation {
    def stop(result: CanPass): IndicesCheckContinuation = Left(result)
    val continue: IndicesCheckContinuation = Right(())
  }
}

object IndicesRule {
  val name = Rule.Name("indices")

  final case class Settings(allowedIndices: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]])

  private sealed trait CanPass
  private object CanPass {
    final case class Yes(indices: Set[IndexName]) extends CanPass
    case object No extends CanPass
  }

  private implicit val stringIndexNameNT: StringTNaturalTransformation[IndexName] =
    StringTNaturalTransformation[IndexName](str => IndexName(NonEmptyString.unsafeFrom(str)), _.value.value)
}

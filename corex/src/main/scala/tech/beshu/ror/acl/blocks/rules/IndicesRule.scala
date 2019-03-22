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

import cats.implicits._
import cats.data.NonEmptySet
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.acl.domain.Action.{mSearchAction, searchAction}
import tech.beshu.ror.acl.domain.IndexName
import tech.beshu.ror.acl.orders._
import tech.beshu.ror.utils.MatcherWithWildcards
import tech.beshu.ror.acl.blocks.rules.IndicesRule.{CanPass, Settings}
import tech.beshu.ror.acl.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.acl.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.ZeroKnowledgeIndexFilter
import tech.beshu.ror.acl.blocks.rules.utils.{Matcher, MatcherWithWildcardsScalaAdapter, StringTNaturalTransformation, ZeroKnowledgeIndexFilterScalaAdapter}
import tech.beshu.ror.acl.blocks.rules.utils.ZeroKnowledgeIndexFilterScalaAdapter.CheckResult
import tech.beshu.ror.acl.blocks.BlockContext
import tech.beshu.ror.acl.blocks.values.{Const, RuntimeValue}
import tech.beshu.ror.acl.request.RequestContext

import scala.collection.JavaConverters._
import scala.collection.SortedSet
import scala.language.postfixOps


class IndicesRule(val settings: Settings)
  extends RegularRule with Logging {

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
      val resolvedIndices = settings.allowedIndices.toList
        .flatMap { v =>
          v.extract(requestContext, blockContext) match {
            case Right(index) => index :: Nil
            case Left(_) => Nil
          }
        }
      new MatcherWithWildcardsScalaAdapter(new MatcherWithWildcards(resolvedIndices.map(_.value).toSet.asJava))
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
    val indices = requestContext.indices
    // 1. Requesting none or all the indices means requesting allowed indices that exist.
    logger.debug("Stage 0")
    if (indices.isEmpty || indices.contains(IndexName.all) || indices.contains(IndexName.wildcard)) {
      val allowedIdxs = matcher.filter(requestContext.allIndicesAndAliases)
      val result =
        if (allowedIdxs.nonEmpty) CanPass.Yes(allowedIdxs)
        else CanPass.No
      return result
    }

    if (requestContext.isReadOnlyRequest) {
      // Handle simple case of single index
      logger.debug("Stage 1")
      indices.toList match {
        case index :: Nil if matcher.`match`(index) =>
          return CanPass.Yes(Set.empty)
        case _ =>
      }
      // ----- Now you requested SOME indices, let's see if and what we can allow in.
      // 2. All indices match by wildcard?
      logger.debug("Stage 2")
      if (matcher.filter(indices) === indices) {
        return CanPass.Yes(Set.empty)
      }
      logger.debug("Stage 2.1")
      // 2.1 Detect at least 1 non-wildcard requested indices that do not exist, ES will naturally return 404, our job is done.
      val real = requestContext.allIndicesAndAliases
      val nonExistent = indices.foldLeft(Set.empty[IndexName]) {
        case (acc, index) if !index.hasWildcard && !real.contains(index) => acc + index
        case (acc, _) => acc
      }

      if (nonExistent.nonEmpty) {
        if (!requestContext.isCompositeRequest) {
          // This goes to 404 naturally, so let it through
          return CanPass.Yes(Set.empty)
        } else {
          val updatedIndices = indices -- nonExistent
          if (updatedIndices.isEmpty) {
            return CanPass.Yes(Set.empty)
          }
        }
      }
      // 3. indices match by reverse-wildcard?
      // Expand requested indices to a subset of indices available in ES
      logger.debug("Stage 3")
      val expansion = expandedIndices(requestContext)
      // --- 4. Your request expands to no actual index, fine with me, it will return 404 on its own!
      logger.debug("Stage 4")

      if (expansion.isEmpty) {
        return CanPass.Yes(Set.empty)
      }
      // ------ Your request expands to one or many available indices, let's see which ones you are allowed to request..
      val allowedExpansion = matcher.filter(expansion)
      // 5. You requested some indices, but NONE were allowed
      logger.debug("Stage 5")
      if (allowedExpansion.isEmpty) {
        // #TODO should I set indices to rule wildcards?
        return CanPass.No
      }
      logger.debug("Stage 6")
      return CanPass.Yes(allowedExpansion)
    }
    else {
      // Write requests
      // Handle <no-index> (#TODO LEGACY)
      logger.debug("Stage 7")
      if (indices.isEmpty && matcher.contains("<no-index>")) {
        return CanPass.Yes(Set.empty)
      } else {
        // Reject write if at least one requested index is not allowed by the rule conf
        logger.debug("Stage 8")
        for (idx <- indices) {
          if (!matcher.`match`(idx)) {
            return CanPass.No
          }
        }
        // Conditions are satisfied
        return CanPass.Yes(Set.empty)
      }
    }
  }

  private def expandedIndices(requestContext: RequestContext): Set[IndexName] = {
    new MatcherWithWildcardsScalaAdapter(new MatcherWithWildcards(requestContext.indices.map(_.value).asJava))
      .filter(requestContext.allIndicesAndAliases)
  }

  private val initialMatcher = {
    val hasVariables = settings.allowedIndices.exists {
      case Const(_) => false
      case _ => true
    }
    if (hasVariables) None
    else Some {
      new MatcherWithWildcardsScalaAdapter(
        new MatcherWithWildcards(settings.allowedIndices.collect { case Const(IndexName(rawValue)) => rawValue } asJava)
      )
    }
  }

  private val matchAll = settings.allowedIndices.exists {
    case Const(IndexName.`wildcard`) => true
    case _ => false
  }

}

object IndicesRule {
  val name = Rule.Name("indices")

  final case class Settings(allowedIndices: NonEmptySet[RuntimeValue[IndexName]])

  private sealed trait CanPass
  private object CanPass {
    final case class Yes(indices: Set[IndexName]) extends CanPass
    case object No extends CanPass
  }

  private implicit val stringIndexNameNT: StringTNaturalTransformation[IndexName] =
    StringTNaturalTransformation[IndexName](IndexName.apply, _.value)
}

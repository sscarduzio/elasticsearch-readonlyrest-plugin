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

import cats.data.NonEmptySet
import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.ZeroKnowledgeIndexFilter
import tech.beshu.ror.accesscontrol.blocks.BlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.IndicesRule.{CanPass, Settings}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.utils.StringTNaturalTransformation.instances.stringIndexNameNT
import tech.beshu.ror.accesscontrol.blocks.rules.utils.TemplateMatcher.findTemplatesIndicesPatterns
import tech.beshu.ror.accesscontrol.blocks.rules.utils.ZeroKnowledgeIndexFilterScalaAdapter.CheckResult
import tech.beshu.ror.accesscontrol.blocks.rules.utils.{IndicesMatcher, MatcherWithWildcardsScalaAdapter, ZeroKnowledgeIndexFilterScalaAdapter}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.domain.Action.{mSearchAction, searchAction}
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.request.RequestContextOps._
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll

import scala.language.postfixOps

class IndicesRule(val settings: Settings)
  extends RegularRule with Logging {

  import IndicesCheckContinuation._
  import tech.beshu.ror.accesscontrol.blocks.rules.IndicesRule.CanPass.No.Reason

  override val name: Rule.Name = IndicesRule.name

  private val zKindexFilter = new ZeroKnowledgeIndexFilterScalaAdapter(new ZeroKnowledgeIndexFilter(true))

  override def check(requestContext: RequestContext,
                     blockContext: BlockContext): Task[RuleResult] = Task {
    if (!requestContext.involvesIndices) Fulfilled(blockContext)
    else if (matchAll) Fulfilled(blockContext)
    else process(requestContext, blockContext)
  }

  private def process(requestContext: RequestContext, blockContext: BlockContext): RuleResult = {
    val resolvedAllowedIndices = resolveAll(settings.allowedIndices.toNonEmptyList, requestContext, blockContext).toSet
    val indicesMatcher = IndicesMatcher.create(resolvedAllowedIndices)

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
            canPass(requestContext, indicesMatcher, resolvedAllowedIndices) match {
              case CanPass.No(Some(Reason.IndexNotExist)) =>
                return Rejected(Cause.IndexNotFound)
              case CanPass.No(_) =>
                return Rejected()
              case CanPass.Yes(indices) =>
                indices
            }
          }
        // Run the remote algorithm (without knowing the remote list of indices)
        val allProcessedIndices = zKindexFilter.check(crossClusterIndices, indicesMatcher.availableIndicesMatcher) match {
          case CheckResult.Ok(processedIndices) => processedIndices ++ processedLocalIndices
          case CheckResult.Failed =>
            return Rejected()
        }

        return Fulfilled(blockContext.withIndices(allProcessedIndices))
      }
    }

    canPass(requestContext, indicesMatcher, resolvedAllowedIndices) match {
      case CanPass.Yes(indices) =>
        Fulfilled(blockContext.withIndices(indices))
      case CanPass.No(Some(Reason.IndexNotExist)) =>
        Rejected(Cause.IndexNotFound)
      case CanPass.No(_) =>
        Rejected()
    }
  }

  private def isSearchAction(requestContext: RequestContext): Boolean =
    requestContext.isReadOnlyRequest && List(searchAction, mSearchAction).contains(requestContext.action)

  private def canPass(requestContext: RequestContext, matcher: IndicesMatcher, resolvedAllowedIndices: Set[IndexName]): CanPass = {
    if (requestContext.isReadOnlyRequest) canReadOnlyRequestPass(requestContext, matcher, resolvedAllowedIndices)
    else canWriteRequestPass(requestContext, matcher, resolvedAllowedIndices)
  }

  private def canReadOnlyRequestPass(requestContext: RequestContext,
                                     matcher: IndicesMatcher,
                                     resolvedAllowedIndices: Set[IndexName]): CanPass = {
    val result = for {
      _ <- templateIndicesPatterns(requestContext, resolvedAllowedIndices)
      _ <- noneOrAllIndices(requestContext, matcher)
      _ <- allIndicesMatchedByWildcard(requestContext, matcher)
      _ <- atLeastOneNonWildcardIndexNotExist(requestContext, matcher)
      _ <- indicesAliases(requestContext, matcher)
    } yield ()
    result.left.getOrElse(CanPass.No())
  }

  private def noneOrAllIndices(requestContext: RequestContext, matcher: IndicesMatcher): IndicesCheckContinuation = {
    logger.debug("Checking - none or all indices ...")
    val indices = requestContext.indices
    val allIndicesAndAliases = requestContext.allIndicesAndAliases.flatMap(_.all)
    if(allIndicesAndAliases.isEmpty) {
      stop(CanPass.Yes(indices))
    } else if (indices.isEmpty || indices.contains(IndexName.all) || indices.contains(IndexName.wildcard)) {
      val allowedIdxs = matcher.filterIndices(allIndicesAndAliases)
      stop(
        if (allowedIdxs.nonEmpty) CanPass.Yes(allowedIdxs)
        else CanPass.No(Reason.IndexNotExist)
      )
    } else {
      continue
    }
  }

  private def allIndicesMatchedByWildcard(requestContext: RequestContext, matcher: IndicesMatcher): IndicesCheckContinuation = {
    logger.debug("Checking if all indices are matched ...")
    val indices = requestContext.indices
    indices.toList match {
      case index :: Nil if !index.hasWildcard =>
        if (matcher.`match`(index)) {
          stop(CanPass.Yes(Set(index)))
        } else {
          continue
        }
      case _ if indices.forall(i => !i.hasWildcard) && matcher.filterIndices(indices) === indices =>
        stop(CanPass.Yes(indices))
      case _ =>
        continue
    }
  }

  private def atLeastOneNonWildcardIndexNotExist(requestContext: RequestContext, matcher: IndicesMatcher): IndicesCheckContinuation = {
    logger.debug("Checking if at least one non-wildcard index doesn't exist ...")
    val indices = requestContext.indices
    val real = requestContext.allIndicesAndAliases.flatMap(_.all)
    val nonExistent = indices.foldLeft(Set.empty[IndexName]) {
      case (acc, index) if !index.hasWildcard && !real.contains(index) => acc + index
      case (acc, _) => acc
    }
    if (nonExistent.nonEmpty && !requestContext.isCompositeRequest) {
      stop(CanPass.No(Reason.IndexNotExist))
    } else if (nonExistent.nonEmpty && (indices -- nonExistent).isEmpty) {
      stop(CanPass.No(Reason.IndexNotExist))
    } else {
      continue
    }
  }

  private def indicesAliases(requestContext: RequestContext, matcher: IndicesMatcher): IndicesCheckContinuation = {
    logger.debug("Checking - indices & aliases ...")
    val allowedRealIndices =
      filterAssumingThatIndicesAreRequestedAndIndicesAreConfigured(requestContext, matcher) ++
      filterAssumingThatIndicesAreRequestedAndAliasesAreConfigured(requestContext, matcher) ++
      filterAssumingThatAliasesAreRequestedAndAliasesAreConfigured(requestContext, matcher) ++
      filterAssumingThatAliasesAreRequestedAndIndicesAreConfigured(requestContext, matcher)
    if(allowedRealIndices.nonEmpty) {
      stop(CanPass.Yes(allowedRealIndices))
    } else {
      stop(CanPass.No(Reason.IndexNotExist))
    }
  }

  private def filterAssumingThatIndicesAreRequestedAndIndicesAreConfigured(requestContext: RequestContext,
                                                                           matcher: IndicesMatcher) = {
    val allIndices = requestContext.allIndicesAndAliases.map(_.index)
    val requestedIndicesNames = requestContext.indices
    val requestedIndices = MatcherWithWildcardsScalaAdapter.create(requestedIndicesNames).filter(allIndices)

    matcher.filterIndices(requestedIndices)
  }

  private def filterAssumingThatIndicesAreRequestedAndAliasesAreConfigured(requestContext: RequestContext,
                                                                           matcher: IndicesMatcher) = {
    // eg. alias A1 of index I1 can be defined with filtering, so result of /I1/_search will be different than
    // result of /A1/_search. It means that if indices are requested and aliases are configured, the result of
    // this kind of method will always be empty set.
    Set.empty[IndexName]
  }

  private def filterAssumingThatAliasesAreRequestedAndAliasesAreConfigured(requestContext: RequestContext,
                                                                           matcher: IndicesMatcher) = {
    val allAliases = requestContext.allIndicesAndAliases.flatMap(_.aliases)
    val requestedAliasesNames = requestContext.indices
    val requestedAliases = MatcherWithWildcardsScalaAdapter.create(requestedAliasesNames).filter(allAliases)

    matcher.filterIndices(requestedAliases)
  }

  private def filterAssumingThatAliasesAreRequestedAndIndicesAreConfigured(requestContext: RequestContext,
                                                                           matcher: IndicesMatcher) = {
    val requestedAliasesNames = requestContext.indices
    val allAliases = requestContext.allIndicesAndAliases.flatMap(_.aliases)
    val requestedAliases = MatcherWithWildcardsScalaAdapter.create(requestedAliasesNames).filter(allAliases)

    val aliasesPerIndex = requestContext.indicesPerAliasMap
    val indicesOfRequestedAliases = requestedAliases.flatMap(aliasesPerIndex.getOrElse(_, Set.empty))
    matcher.filterIndices(indicesOfRequestedAliases)
  }

  private def templateIndicesPatterns(requestContext: RequestContext, allowedIndices: Set[IndexName]): IndicesCheckContinuation = {
    logger.debug("Checking - template indices patterns...")
    requestContext match {
      case rc if rc.action.isTemplate || rc.uriPath.isCatTemplatePath =>
        if(rc.templateIndicesPatterns.nonEmpty) {
          val allowed = findTemplatesIndicesPatterns(rc.templateIndicesPatterns, allowedIndices)
          if (allowed.nonEmpty) stop(CanPass.Yes(allowed))
          else stop(CanPass.Yes(allowedIndices))
        } else {
          stop(CanPass.Yes(allowedIndices))
        }
      case _ =>
        continue
    }
  }

  private def canWriteRequestPass(requestContext: RequestContext,
                                  matcher: IndicesMatcher,
                                  resolvedAllowedIndices: Set[IndexName]): CanPass = {
    val result = for {
      _ <- writeTemplateIndicesPatterns(requestContext, resolvedAllowedIndices)
      _ <- generalWriteRequest(requestContext, matcher)
    } yield ()
    result.left.getOrElse(CanPass.No())
  }

  private def writeTemplateIndicesPatterns(requestContext: RequestContext, allowedIndices: Set[IndexName]): IndicesCheckContinuation = {
    logger.debug("Checking - write template request...")
    requestContext match {
      case rc if rc.action.isTemplate || rc.uriPath.isCatTemplatePath =>
        val allowed = findTemplatesIndicesPatterns(rc.templateIndicesPatterns, allowedIndices)
        if (allowed.nonEmpty) stop(CanPass.Yes(allowed))
        else stop(CanPass.No())
      case _ =>
        continue
    }
  }

  private def generalWriteRequest(requestContext: RequestContext, matcher: IndicesMatcher): IndicesCheckContinuation = {
    logger.debug("Checking - write request ...")
    val indices = requestContext.indices
    // Write requests
    // Handle <no-index> (#TODO LEGACY)
    logger.debug("Stage 7")
    if (indices.isEmpty && matcher.contains("<no-index>")) {
      stop(CanPass.Yes(Set.empty))
    } else {
      // Reject write if at least one requested index is not allowed by the rule conf
      logger.debug("Stage 8")
      val result = indices.foldLeft(CanPass.Yes(Set.empty): CanPass) {
        case (CanPass.Yes(_), index) =>
          if (matcher.`match`(index)) CanPass.Yes(Set.empty)
          else CanPass.No()
        case (CanPass.No(_), _) => CanPass.No()
      }
      stop(result)
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
    final case class No(reason: Option[No.Reason] = None) extends CanPass
    object No {
      def apply(reason: Reason): No = new No(Some(reason))

      sealed trait Reason
      object Reason {
        case object IndexNotExist extends Reason
      }
    }
  }
}
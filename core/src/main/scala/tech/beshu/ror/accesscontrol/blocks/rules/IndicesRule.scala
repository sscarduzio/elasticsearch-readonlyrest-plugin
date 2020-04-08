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
import tech.beshu.ror.accesscontrol.blocks.BlockContext.GeneralIndexRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.{CurrentUserMetadataRequestBlockContextUpdater, GeneralIndexRequestBlockContextUpdater, GeneralNonIndexRequestBlockContextUpdater, RepositoryRequestBlockContextUpdater, SnapshotRequestBlockContextUpdater, TemplateRequestBlockContextUpdater}
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
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.Action.{mSearchAction, searchAction}
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.request.RequestContextOps._
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll

class IndicesRule(val settings: Settings)
  extends RegularRule with Logging {

  import IndicesCheckContinuation._
  import tech.beshu.ror.accesscontrol.blocks.rules.IndicesRule.CanPass.No.Reason

  override val name: Rule.Name = IndicesRule.name

  private val zKindexFilter = new ZeroKnowledgeIndexFilterScalaAdapter(new ZeroKnowledgeIndexFilter(true))

  override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    if (matchAll) Fulfilled(blockContext)
    else {
      BlockContextUpdater[B] match {
        case CurrentUserMetadataRequestBlockContextUpdater => Fulfilled(blockContext)
        case GeneralNonIndexRequestBlockContextUpdater => Fulfilled(blockContext)
        case RepositoryRequestBlockContextUpdater => Fulfilled(blockContext)
        case SnapshotRequestBlockContextUpdater => Fulfilled(blockContext)
        case TemplateRequestBlockContextUpdater => Fulfilled(blockContext)
        case GeneralIndexRequestBlockContextUpdater => process(blockContext)
      }
    }
  }

  private def process(blockContext: GeneralIndexRequestBlockContext): RuleResult[GeneralIndexRequestBlockContext] = {
    val resolvedAllowedIndices = resolveAll(settings.allowedIndices.toNonEmptyList, blockContext).toSet
    val indicesMatcher = IndicesMatcher.create(resolvedAllowedIndices)
    val requestContext = blockContext.requestContext

    // Cross cluster search awareness
    if (isSearchAction(requestContext)) {
      val (crossClusterIndices, localIndices) =
        if (!requestContext.hasRemoteClusters) {
          // Only requested X-cluster when we don't have remote, will return empty.
          val crossClusterIndices = blockContext.indices.filter(_.isClusterIndex)
          if (blockContext.indices.nonEmpty && blockContext.indices.size == crossClusterIndices.size) {
            return Fulfilled(blockContext)
          }
          // If you requested local + X-cluster indices while we don't have remotes, it's like you asked for only local indices.
          (Set.empty[IndexName], blockContext.indices.filter(index => !index.isClusterIndex))
        } else {
          blockContext.indices.partition(_.isClusterIndex)
        }

      // Scatter gather for local and remote indices barring algorithms
      if (crossClusterIndices.nonEmpty) {
        // Run the local algorithm
        val processedLocalIndices =
          if (localIndices.isEmpty && crossClusterIndices.nonEmpty) {
            // Don't run locally if only have crossCluster, otherwise you'll resolve the equivalent of "*"
            localIndices
          } else {
            canPass(blockContext, indicesMatcher, resolvedAllowedIndices) match {
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

    canPass(blockContext, indicesMatcher, resolvedAllowedIndices) match {
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

  private def canPass(blockContext: GeneralIndexRequestBlockContext, matcher: IndicesMatcher, resolvedAllowedIndices: Set[IndexName]): CanPass = {
    if (blockContext.requestContext.isReadOnlyRequest) canReadOnlyRequestPass(blockContext, matcher, resolvedAllowedIndices)
    else canWriteRequestPass(blockContext, matcher, resolvedAllowedIndices)
  }

  private def canReadOnlyRequestPass(blockContext: GeneralIndexRequestBlockContext,
                                     matcher: IndicesMatcher,
                                     resolvedAllowedIndices: Set[IndexName]): CanPass = {
    val result = for {
      _ <- templateIndicesPatterns(blockContext, resolvedAllowedIndices)
      _ <- noneOrAllIndices(blockContext, matcher)
      _ <- allIndicesMatchedByWildcard(blockContext, matcher)
      _ <- atLeastOneNonWildcardIndexNotExist(blockContext, matcher)
      _ <- indicesAliases(blockContext, matcher)
    } yield ()
    result.left.getOrElse(CanPass.No())
  }

  private def noneOrAllIndices(blockContext: GeneralIndexRequestBlockContext, matcher: IndicesMatcher): IndicesCheckContinuation = {
    logger.debug("Checking - none or all indices ...")
    val indices = blockContext.indices
    val allIndicesAndAliases = blockContext.requestContext.allIndicesAndAliases.flatMap(_.all)
    if (allIndicesAndAliases.isEmpty) {
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

  private def allIndicesMatchedByWildcard(blockContext: GeneralIndexRequestBlockContext, matcher: IndicesMatcher): IndicesCheckContinuation = {
    logger.debug("Checking if all indices are matched ...")
    val indices = blockContext.indices
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

  private def atLeastOneNonWildcardIndexNotExist(blockContext: GeneralIndexRequestBlockContext,
                                                 matcher: IndicesMatcher): IndicesCheckContinuation = {
    logger.debug("Checking if at least one non-wildcard index doesn't exist ...")
    val indices = blockContext.indices
    val real = blockContext.requestContext.allIndicesAndAliases.flatMap(_.all)
    val nonExistent = indices.foldLeft(Set.empty[IndexName]) {
      case (acc, index) if !index.hasWildcard && !real.contains(index) => acc + index
      case (acc, _) => acc
    }
    if (nonExistent.nonEmpty && !blockContext.requestContext.isCompositeRequest) {
      stop(CanPass.No(Reason.IndexNotExist))
    } else if (nonExistent.nonEmpty && (indices -- nonExistent).isEmpty) {
      stop(CanPass.No(Reason.IndexNotExist))
    } else {
      continue
    }
  }

  private def indicesAliases(blockContext: GeneralIndexRequestBlockContext, matcher: IndicesMatcher): IndicesCheckContinuation = {
    logger.debug("Checking - indices & aliases ...")
    val allowedRealIndices =
      filterAssumingThatIndicesAreRequestedAndIndicesAreConfigured(blockContext, matcher) ++
        filterAssumingThatIndicesAreRequestedAndAliasesAreConfigured(blockContext, matcher) ++
        filterAssumingThatAliasesAreRequestedAndAliasesAreConfigured(blockContext, matcher) ++
        filterAssumingThatAliasesAreRequestedAndIndicesAreConfigured(blockContext, matcher)
    if (allowedRealIndices.nonEmpty) {
      stop(CanPass.Yes(allowedRealIndices))
    } else {
      stop(CanPass.No(Reason.IndexNotExist))
    }
  }

  private def filterAssumingThatIndicesAreRequestedAndIndicesAreConfigured(blockContext: GeneralIndexRequestBlockContext,
                                                                           matcher: IndicesMatcher) = {
    val allIndices = blockContext.requestContext.allIndicesAndAliases.map(_.index)
    val requestedIndicesNames = blockContext.indices
    val requestedIndices = MatcherWithWildcardsScalaAdapter.create(requestedIndicesNames).filter(allIndices)

    matcher.filterIndices(requestedIndices)
  }

  private def filterAssumingThatIndicesAreRequestedAndAliasesAreConfigured(blockContext: GeneralIndexRequestBlockContext,
                                                                           matcher: IndicesMatcher) = {
    // eg. alias A1 of index I1 can be defined with filtering, so result of /I1/_search will be different than
    // result of /A1/_search. It means that if indices are requested and aliases are configured, the result of
    // this kind of method will always be empty set.
    Set.empty[IndexName]
  }

  private def filterAssumingThatAliasesAreRequestedAndAliasesAreConfigured(blockContext: GeneralIndexRequestBlockContext,
                                                                           matcher: IndicesMatcher) = {
    val allAliases = blockContext.requestContext.allIndicesAndAliases.flatMap(_.aliases)
    val requestedAliasesNames = blockContext.indices
    val requestedAliases = MatcherWithWildcardsScalaAdapter.create(requestedAliasesNames).filter(allAliases)

    matcher.filterIndices(requestedAliases)
  }

  private def filterAssumingThatAliasesAreRequestedAndIndicesAreConfigured(blockContext: GeneralIndexRequestBlockContext,
                                                                           matcher: IndicesMatcher) = {
    val requestedAliasesNames = blockContext.indices
    val allAliases = blockContext.requestContext.allIndicesAndAliases.flatMap(_.aliases)
    val requestedAliases = MatcherWithWildcardsScalaAdapter.create(requestedAliasesNames).filter(allAliases)

    val aliasesPerIndex = blockContext.requestContext.indicesPerAliasMap
    val indicesOfRequestedAliases = requestedAliases.flatMap(aliasesPerIndex.getOrElse(_, Set.empty))
    matcher.filterIndices(indicesOfRequestedAliases)
  }

  private def templateIndicesPatterns(blockContext: GeneralIndexRequestBlockContext,
                                      allowedIndices: Set[IndexName]): IndicesCheckContinuation = {
    logger.debug("Checking - template indices patterns...")
    blockContext.requestContext match {
      case rc if rc.action.isTemplate || rc.uriPath.isCatTemplatePath =>
        if (rc.templateIndicesPatterns.nonEmpty) {
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

  private def canWriteRequestPass(blockContext: GeneralIndexRequestBlockContext,
                                  matcher: IndicesMatcher,
                                  resolvedAllowedIndices: Set[IndexName]): CanPass = {
    val result = for {
      _ <- writeTemplateIndicesPatterns(blockContext, resolvedAllowedIndices)
      _ <- generalWriteRequest(blockContext, matcher)
    } yield ()
    result.left.getOrElse(CanPass.No())
  }

  private def writeTemplateIndicesPatterns(blockContext: GeneralIndexRequestBlockContext,
                                           allowedIndices: Set[IndexName]): IndicesCheckContinuation = {
    logger.debug("Checking - write template request...")
    blockContext.requestContext match {
      case rc if rc.action.isTemplate || rc.uriPath.isCatTemplatePath =>
        val allowed = findTemplatesIndicesPatterns(rc.templateIndicesPatterns, allowedIndices)
        if (allowed.nonEmpty) stop(CanPass.Yes(allowed))
        else stop(CanPass.No())
      case _ =>
        continue
    }
  }

  private def generalWriteRequest(blockContext: GeneralIndexRequestBlockContext, matcher: IndicesMatcher): IndicesCheckContinuation = {
    logger.debug("Checking - write request ...")
    val indices = blockContext.indices
    // Write requests
    // Handle <no-index> (#TODO LEGACY)
    logger.debug("Stage 7")
    if (indices.isEmpty && matcher.contains("<no-index>")) {
      stop(CanPass.Yes(indices))
    } else {
      // Reject write if at least one requested index is not allowed by the rule conf
      logger.debug("Stage 8")
      stop {
        indices.find(index => !matcher.`match`(index)) match {
          case Some(_) => CanPass.No()
          case None => CanPass.Yes(indices)
        }
      }
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
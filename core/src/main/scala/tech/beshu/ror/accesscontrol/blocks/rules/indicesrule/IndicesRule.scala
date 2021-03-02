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
package tech.beshu.ror.accesscontrol.blocks.rules.indicesrule

import cats.data.NonEmptySet
import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.BlockContext.MultiIndexRequestBlockContext.Indices
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{AliasRequestBlockContext, HasIndexPacks, SnapshotRequestBlockContext}
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.{AliasRequestBlockContextUpdater, CurrentUserMetadataRequestBlockContextUpdater, FilterableMultiRequestBlockContextUpdater, FilterableRequestBlockContextUpdater, GeneralIndexRequestBlockContextUpdater, GeneralNonIndexRequestBlockContextUpdater, MultiIndexRequestBlockContextUpdater, RepositoryRequestBlockContextUpdater, SnapshotRequestBlockContextUpdater, TemplateRequestBlockContextUpdater}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause.IndexNotFound
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.domain._
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.IndicesRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.domain.CanPass
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.domain.CanPass.No.Reason
import tech.beshu.ror.accesscontrol.blocks.rules.utils.StringTNaturalTransformation.instances.stringIndexNameNT
import tech.beshu.ror.accesscontrol.blocks.rules.utils.ZeroKnowledgeIndexFilterScalaAdapter.CheckResult
import tech.beshu.ror.accesscontrol.blocks.rules.utils.{IndicesMatcher, MatcherWithWildcardsScalaAdapter, UniqueIdentifierGenerator, ZeroKnowledgeIndexFilterScalaAdapter}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, BlockContextWithIndexPacksUpdater, BlockContextWithIndicesUpdater}
import tech.beshu.ror.accesscontrol.domain.IndexName
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.request.RequestContextOps._
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll
import tech.beshu.ror.utils.ZeroKnowledgeIndexFilter

class IndicesRule(override val settings: Settings,
                  override val identifierGenerator: UniqueIdentifierGenerator)
  extends RegularRule
    with AllTemplateIndices
    with Logging {

  import IndicesCheckContinuation._
  import IndicesRule._

  override val name: Rule.Name = IndicesRule.name

  private val zKindexFilter = new ZeroKnowledgeIndexFilterScalaAdapter(new ZeroKnowledgeIndexFilter(true))

  override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    BlockContextUpdater[B] match {
      case CurrentUserMetadataRequestBlockContextUpdater => processRequestWithoutIndices(blockContext)
      case GeneralNonIndexRequestBlockContextUpdater => processRequestWithoutIndices(blockContext)
      case RepositoryRequestBlockContextUpdater => processRequestWithoutIndices(blockContext)
      case SnapshotRequestBlockContextUpdater => processSnapshotRequest(blockContext)
      case GeneralIndexRequestBlockContextUpdater => processIndicesRequest(blockContext)
      case FilterableRequestBlockContextUpdater => processIndicesRequest(blockContext)
      case MultiIndexRequestBlockContextUpdater => processIndicesPacks(blockContext)
      case FilterableMultiRequestBlockContextUpdater => processIndicesPacks(blockContext)
      case AliasRequestBlockContextUpdater => processAliasRequest(blockContext)
      case TemplateRequestBlockContextUpdater => processTemplateRequest(blockContext)
    }
  }

  private def processRequestWithoutIndices[B <: BlockContext](blockContext: B): RuleResult[B] = {
    if(settings.mustInvolveIndices) Rejected()
    else Fulfilled(blockContext)
  }

  private def processIndicesRequest[B <: BlockContext: BlockContextWithIndicesUpdater](blockContext: B): RuleResult[B] = {
    if (matchAll) {
      Fulfilled(blockContext)
    } else {
      val allAllowedIndices = resolveAll(settings.allowedIndices.toNonEmptyList, blockContext).toSet
      val result = processIndices(blockContext.requestContext, allAllowedIndices, blockContext.indices)
      result match {
        case ProcessResult.Ok(filteredIndices) => Fulfilled(blockContext.withIndices(filteredIndices, allAllowedIndices))
        case ProcessResult.Failed(cause) => Rejected(cause)
      }
    }
  }

  private def processIndicesPacks[B <: BlockContext: BlockContextWithIndexPacksUpdater: HasIndexPacks](blockContext: B): RuleResult[B] = {
    if (matchAll) {
      Fulfilled(blockContext)
    } else {
      import tech.beshu.ror.accesscontrol.blocks.BlockContext.HasIndexPacks._
      def atLeastOneFound(indices: Vector[Indices]) = indices.exists(_.isInstanceOf[Indices.Found])

      val resolvedAllowedIndices = resolveAll(settings.allowedIndices.toNonEmptyList, blockContext).toSet
      val result = blockContext
        .indexPacks
        .foldLeft(Vector.empty[Indices].asRight[Option[Cause]]) {
          case (Right(currentList), pack) => pack match {
            case Indices.Found(indices) =>
              val result = processIndices(
                blockContext.requestContext,
                resolvedAllowedIndices,
                indices
              )
              result match {
                case ProcessResult.Ok(narrowedIndices) => Right(currentList :+ Indices.Found(narrowedIndices))
                case ProcessResult.Failed(Some(Cause.IndexNotFound)) => Right(currentList :+ Indices.NotFound)
                case ProcessResult.Failed(cause) => Left(cause)
              }
            case Indices.NotFound =>
              Right(currentList :+ Indices.NotFound)
          }
          case (result@Left(_), _) =>
            result
        }
      result match {
        case Right(indices) if atLeastOneFound(indices) => Fulfilled(blockContext.withIndicesPacks(indices.toList))
        case Right(_) => Rejected(Cause.IndexNotFound)
        case Left(cause) => Rejected(cause)
      }
    }
  }

  private def processIndices(requestContext: RequestContext,
                             resolvedAllowedIndices: Set[IndexName],
                             indices: Set[IndexName]): ProcessResult = {
    val indicesMatcher = IndicesMatcher.create(resolvedAllowedIndices)
    // Cross cluster search awareness
    if (isSearchAction(requestContext)) {
      val (crossClusterIndices, localIndices) =
        if (!requestContext.hasRemoteClusters) {
          // Only requested X-cluster when we don't have remote, will return empty.
          val crossClusterIndices = indices.filter(_.isClusterIndex)
          if (indices.nonEmpty && indices.size == crossClusterIndices.size) {
            return ProcessResult.Ok(indices)
          }
          // If you requested local + X-cluster indices while we don't have remotes, it's like you asked for only local indices.
          (Set.empty[IndexName], indices.filter(index => !index.isClusterIndex))
        } else {
          indices.partition(_.isClusterIndex)
        }

      // Scatter gather for local and remote indices barring algorithms
      if (crossClusterIndices.nonEmpty) {
        // Run the local algorithm
        val processedLocalIndices =
          if (localIndices.isEmpty && crossClusterIndices.nonEmpty) {
            // Don't run locally if only have crossCluster, otherwise you'll resolve the equivalent of "*"
            localIndices
          } else {
            canPass(requestContext, indices, indicesMatcher) match {
              case CanPass.No(Some(Reason.IndexNotExist)) =>
                Set.empty[IndexName]
              case CanPass.No(_) =>
                Set.empty[IndexName]
              case CanPass.Yes(narrowedIndices) =>
                narrowedIndices
            }
          }
        // Run the remote algorithm (without knowing the remote list of indices)
        val allProcessedIndices = zKindexFilter.check(crossClusterIndices, indicesMatcher.availableIndicesMatcher) match {
          case CheckResult.Ok(processedIndices) => processedIndices ++ processedLocalIndices
          case CheckResult.Failed => processedLocalIndices
        }

        return if (allProcessedIndices.nonEmpty) ProcessResult.Ok(allProcessedIndices)
        else ProcessResult.Failed(Some(IndexNotFound))
      }
    }

    canPass(requestContext, indices, indicesMatcher) match {
      case CanPass.Yes(narrowedIndices) =>
        ProcessResult.Ok(narrowedIndices)
      case CanPass.No(Some(Reason.IndexNotExist)) =>
        ProcessResult.Failed(Some(Cause.IndexNotFound))
      case CanPass.No(_) =>
        ProcessResult.Failed(None)
    }
  }

  private def isSearchAction(requestContext: RequestContext): Boolean =
    requestContext.isReadOnlyRequest && requestContext.action.isSearchAction

  private def canPass(requestContext: RequestContext,
                      indices: Set[IndexName],
                      matcher: IndicesMatcher): CanPass[Set[IndexName]] = {
    if (requestContext.isReadOnlyRequest) canIndicesReadOnlyRequestPass(requestContext, indices, matcher)
    else canIndicesWriteRequestPass(requestContext, indices, matcher)
  }

  private def canIndicesReadOnlyRequestPass(requestContext: RequestContext,
                                            indices: Set[IndexName],
                                            matcher: IndicesMatcher): CanPass[Set[IndexName]] = {
    val result = for {
      _ <- noneOrAllIndices(requestContext, indices, matcher)
      _ <- allIndicesMatchedByWildcard(requestContext, indices, matcher)
      _ <- indicesAliases(requestContext, indices, matcher)
    } yield ()
    result.left.getOrElse(CanPass.No())
  }

  private def noneOrAllIndices(requestContext: RequestContext,
                               indices: Set[IndexName],
                               matcher: IndicesMatcher): CheckContinuation[Set[IndexName]] = {
    logger.debug(s"[${requestContext.id.show}] Checking - none or all indices ...")
    val allIndicesAndAliases = requestContext.allIndicesAndAliases.flatMap(_.all)
    if (indices.isEmpty || indices.contains(IndexName.all) || indices.contains(IndexName.wildcard)) {
      val allowedIdxs = matcher.filterIndices(allIndicesAndAliases)
      stop(
        if (allowedIdxs.nonEmpty) CanPass.Yes(allowedIdxs)
        else CanPass.No(Reason.IndexNotExist)
      )
    } else {
      continue[Set[IndexName]]
    }
  }

  private def allIndicesMatchedByWildcard(requestContext: RequestContext,
                                          indices: Set[IndexName],
                                          matcher: IndicesMatcher): CheckContinuation[Set[IndexName]] = {
    logger.debug(s"[${requestContext.id.show}] Checking if all indices are matched ...")
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
        continue[Set[IndexName]]
    }
  }

  private def indicesAliases(requestContext: RequestContext,
                             indices: Set[IndexName],
                             matcher: IndicesMatcher): CheckContinuation[Set[IndexName]] = {
    logger.debug(s"[${requestContext.id.show}] Checking - indices & aliases ...")
    val allowedRealIndices =
      filterAssumingThatIndicesAreRequestedAndIndicesAreConfigured(requestContext, indices, matcher) ++
        filterAssumingThatIndicesAreRequestedAndAliasesAreConfigured() ++
        filterAssumingThatAliasesAreRequestedAndAliasesAreConfigured(requestContext, indices, matcher) ++
        filterAssumingThatAliasesAreRequestedAndIndicesAreConfigured(requestContext, indices, matcher)
    if (allowedRealIndices.nonEmpty) {
      stop(CanPass.Yes(allowedRealIndices))
    } else {
      stop(CanPass.No(Reason.IndexNotExist))
    }
  }

  private def filterAssumingThatIndicesAreRequestedAndIndicesAreConfigured(requestContext: RequestContext,
                                                                           indices: Set[IndexName],
                                                                           matcher: IndicesMatcher) = {
    val allIndices = requestContext.allIndicesAndAliases.map(_.index)
    val requestedIndicesNames = indices
    val requestedIndices = MatcherWithWildcardsScalaAdapter.create(requestedIndicesNames).filter(allIndices)

    matcher.filterIndices(requestedIndices)
  }

  private def filterAssumingThatIndicesAreRequestedAndAliasesAreConfigured() = {
    // eg. alias A1 of index I1 can be defined with filtering, so result of /I1/_search will be different than
    // result of /A1/_search. It means that if indices are requested and aliases are configured, the result of
    // this kind of method will always be empty set.
    Set.empty[IndexName]
  }

  private def filterAssumingThatAliasesAreRequestedAndAliasesAreConfigured(requestContext: RequestContext,
                                                                           indices: Set[IndexName],
                                                                           matcher: IndicesMatcher) = {
    val allAliases = requestContext.allIndicesAndAliases.flatMap(_.aliases)
    val requestedAliasesNames = indices
    val requestedAliases = MatcherWithWildcardsScalaAdapter.create(requestedAliasesNames).filter(allAliases)

    matcher.filterIndices(requestedAliases)
  }

  private def filterAssumingThatAliasesAreRequestedAndIndicesAreConfigured(requestContext: RequestContext,
                                                                           indices: Set[IndexName],
                                                                           matcher: IndicesMatcher) = {
    val requestedAliasesNames = indices
    val allAliases = requestContext.allIndicesAndAliases.flatMap(_.aliases)
    val requestedAliases = MatcherWithWildcardsScalaAdapter.create(requestedAliasesNames).filter(allAliases)

    val aliasesPerIndex = requestContext.indicesPerAliasMap
    val indicesOfRequestedAliases = requestedAliases.flatMap(aliasesPerIndex.getOrElse(_, Set.empty))
    matcher.filterIndices(indicesOfRequestedAliases)
  }

  private def canIndicesWriteRequestPass(requestContext: RequestContext,
                                         indices: Set[IndexName],
                                         matcher: IndicesMatcher): CanPass[Set[IndexName]] = {
    val result = for {
      _ <- generalWriteRequest(requestContext, indices, matcher)
    } yield ()
    result.left.getOrElse(CanPass.No())
  }

  private def generalWriteRequest(requestContext: RequestContext,
                                  indices: Set[IndexName],
                                  matcher: IndicesMatcher): CheckContinuation[Set[IndexName]] = {
    logger.debug(s"[${requestContext.id.show}] Checking - write request ...")
    // Write requests
    // Handle <no-index> (#TODO LEGACY)
    logger.debug(s"[${requestContext.id.show}] Stage 7")
    if (indices.isEmpty && matcher.contains("<no-index>")) {
      stop(CanPass.Yes(indices))
    } else {
      // Reject write if at least one requested index is not allowed by the rule conf
      logger.debug(s"[${requestContext.id.show}] Stage 8")
      stop {
        indices.find(index => !matcher.`match`(index)) match {
          case Some(_) => CanPass.No()
          case None => CanPass.Yes(indices)
        }
      }
    }
  }

  private def processAliasRequest(blockContext: AliasRequestBlockContext): RuleResult[AliasRequestBlockContext] = {
    if (matchAll) {
      Fulfilled(blockContext)
    } else {
      val resolvedAllowedIndices = resolveAll(settings.allowedIndices.toNonEmptyList, blockContext).toSet
      val indicesResult = processIndices(blockContext.requestContext, resolvedAllowedIndices, blockContext.indices)
      val aliasesResult = processIndices(blockContext.requestContext, resolvedAllowedIndices, blockContext.aliases)
      (indicesResult, aliasesResult) match {
        case (ProcessResult.Ok(indices), ProcessResult.Ok(aliases)) =>
          Fulfilled(blockContext.withIndices(indices).withAliases(aliases))
        case (ProcessResult.Failed(cause), _) => Rejected(cause)
        case (_, ProcessResult.Failed(Some(Cause.IndexNotFound))) => Rejected(Some(Cause.AliasNotFound))
        case (_, ProcessResult.Failed(cause)) => Rejected(cause)
      }
    }
  }

  private def processSnapshotRequest(blockContext: SnapshotRequestBlockContext): RuleResult[SnapshotRequestBlockContext] = {
    if (matchAll) Fulfilled(blockContext)
    else if(blockContext.filteredIndices.isEmpty) processRequestWithoutIndices(blockContext)
    else processIndicesRequest(blockContext)
  }

  private val matchAll = settings.allowedIndices.exists {
    case AlreadyResolved(indices) if indices.contains_(IndexName.`wildcard`) => true
    case _ => false
  }

}

object IndicesRule {
  val name = Rule.Name("indices")

  final case class Settings(allowedIndices: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                            mustInvolveIndices: Boolean)

  private sealed trait ProcessResult
  private object ProcessResult {
    final case class Ok(indices: Set[IndexName]) extends ProcessResult
    final case class Failed(cause: Option[Cause]) extends ProcessResult
  }

}
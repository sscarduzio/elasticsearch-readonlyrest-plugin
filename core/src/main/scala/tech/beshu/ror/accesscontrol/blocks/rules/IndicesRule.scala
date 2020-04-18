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
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{GeneralIndexRequestBlockContext, TemplateRequestBlockContext}
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.{CurrentUserMetadataRequestBlockContextUpdater, GeneralIndexRequestBlockContextUpdater, GeneralNonIndexRequestBlockContextUpdater, RepositoryRequestBlockContextUpdater, SnapshotRequestBlockContextUpdater, TemplateRequestBlockContextUpdater}
import tech.beshu.ror.accesscontrol.blocks.rules.IndicesRule.{CanPass, Settings}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.rules.utils.StringTNaturalTransformation.instances.stringIndexNameNT
import tech.beshu.ror.accesscontrol.blocks.rules.utils.TemplateMatcher.filterAllowedTemplateIndexPatterns
import tech.beshu.ror.accesscontrol.blocks.rules.utils.ZeroKnowledgeIndexFilterScalaAdapter.CheckResult
import tech.beshu.ror.accesscontrol.blocks.rules.utils.{IndicesMatcher, MatcherWithWildcardsScalaAdapter, TemplateMatcher, ZeroKnowledgeIndexFilterScalaAdapter}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable.AlreadyResolved
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater}
import tech.beshu.ror.accesscontrol.domain.Action.{mSearchAction, searchAction}
import tech.beshu.ror.accesscontrol.domain.{IndexName, Template}
import tech.beshu.ror.accesscontrol.orders._
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.accesscontrol.request.RequestContext
import tech.beshu.ror.accesscontrol.request.RequestContextOps._
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

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
        case GeneralIndexRequestBlockContextUpdater => processIndicesRequest(blockContext)
        case TemplateRequestBlockContextUpdater => processTemplateRequest(blockContext)
      }
    }
  }

  private def processIndicesRequest(blockContext: GeneralIndexRequestBlockContext): RuleResult[GeneralIndexRequestBlockContext] = {
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
            canPass(blockContext, indicesMatcher) match {
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

    canPass(blockContext, indicesMatcher) match {
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

  private def canPass(blockContext: GeneralIndexRequestBlockContext,
                      matcher: IndicesMatcher): CanPass[Set[IndexName]] = {
    if (blockContext.requestContext.isReadOnlyRequest) canIndicesReadOnlyRequestPass(blockContext, matcher)
    else canIndicesWriteRequestPass(blockContext, matcher)
  }

  private def canIndicesReadOnlyRequestPass(blockContext: GeneralIndexRequestBlockContext,
                                            matcher: IndicesMatcher): CanPass[Set[IndexName]] = {
    val result = for {
      _ <- noneOrAllIndices(blockContext, matcher)
      _ <- allIndicesMatchedByWildcard(blockContext, matcher)
      _ <- atLeastOneNonWildcardIndexNotExist(blockContext)
      _ <- indicesAliases(blockContext, matcher)
    } yield ()
    result.left.getOrElse(CanPass.No())
  }

  private def noneOrAllIndices(blockContext: GeneralIndexRequestBlockContext,
                               matcher: IndicesMatcher): CheckContinuation[Set[IndexName]] = {
    logger.debug(s"[${blockContext.requestContext.id.show}] Checking - none or all indices ...")
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
      continue[Set[IndexName]]
    }
  }

  private def allIndicesMatchedByWildcard(blockContext: GeneralIndexRequestBlockContext,
                                          matcher: IndicesMatcher): CheckContinuation[Set[IndexName]] = {
    logger.debug(s"[${blockContext.requestContext.id.show}] Checking if all indices are matched ...")
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
        continue[Set[IndexName]]
    }
  }

  private def atLeastOneNonWildcardIndexNotExist(blockContext: GeneralIndexRequestBlockContext): CheckContinuation[Set[IndexName]] = {
    logger.debug(s"[${blockContext.requestContext.id.show}] Checking if at least one non-wildcard index doesn't exist ...")
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

  private def indicesAliases(blockContext: GeneralIndexRequestBlockContext,
                             matcher: IndicesMatcher): CheckContinuation[Set[IndexName]] = {
    logger.debug(s"[${blockContext.requestContext.id.show}] Checking - indices & aliases ...")
    val allowedRealIndices =
      filterAssumingThatIndicesAreRequestedAndIndicesAreConfigured(blockContext, matcher) ++
        filterAssumingThatIndicesAreRequestedAndAliasesAreConfigured() ++
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

  private def filterAssumingThatIndicesAreRequestedAndAliasesAreConfigured() = {
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

  private def canIndicesWriteRequestPass(blockContext: GeneralIndexRequestBlockContext,
                                         matcher: IndicesMatcher): CanPass[Set[IndexName]] = {
    val result = for {
      _ <- generalWriteRequest(blockContext, matcher)
    } yield ()
    result.left.getOrElse(CanPass.No())
  }

  private def generalWriteRequest(blockContext: GeneralIndexRequestBlockContext,
                                  matcher: IndicesMatcher): CheckContinuation[Set[IndexName]] = {
    logger.debug(s"[${blockContext.requestContext.id.show}] Checking - write request ...")
    val indices = blockContext.indices
    // Write requests
    // Handle <no-index> (#TODO LEGACY)
    logger.debug(s"[${blockContext.requestContext.id.show}] Stage 7")
    if (indices.isEmpty && matcher.contains("<no-index>")) {
      stop(CanPass.Yes(indices))
    } else {
      // Reject write if at least one requested index is not allowed by the rule conf
      logger.debug(s"[${blockContext.requestContext.id.show}] Stage 8")
      stop {
        indices.find(index => !matcher.`match`(index)) match {
          case Some(_) => CanPass.No()
          case None => CanPass.Yes(indices)
        }
      }
    }
  }

  private def processTemplateRequest(blockContext: TemplateRequestBlockContext): RuleResult[TemplateRequestBlockContext] = {
    val resolvedAllowedIndices = resolveAll(settings.allowedIndices.toNonEmptyList, blockContext).toSet

    val canPass = if (blockContext.requestContext.isReadOnlyRequest) {
      canTemplatesReadOnlyRequestPass(blockContext, resolvedAllowedIndices)
    } else {
      val result = for {
        _ <- canTemplateBeOverwritten(blockContext, resolvedAllowedIndices)
        _ <- canAddTemplateRequestPass(blockContext, resolvedAllowedIndices)
        _ <- canTemplatesWriteRequestPass(blockContext, resolvedAllowedIndices)
      } yield ()
      result.left.getOrElse(CanPass.No())
    }

    canPass match {
      case CanPass.Yes(templates) =>
        Fulfilled(blockContext.withTemplates(templates))
      case CanPass.No(Some(Reason.IndexNotExist)) =>
        Rejected(Cause.IndexNotFound)
      case CanPass.No(_) =>
        Rejected()
    }
  }

  private def canTemplatesReadOnlyRequestPass(blockContext: TemplateRequestBlockContext,
                                              allowedIndices: Set[IndexName]): CanPass[Set[Template]] = {
    logger.debug(s"[${blockContext.requestContext.id.show}] Checking - template readonly request indices patterns ...")
    CanPass.Yes {
      blockContext
        .templates
        .flatMap { template =>
          val filtered = filterAllowedTemplateIndexPatterns(template.patterns.toSet, allowedIndices)
          if (filtered.nonEmpty) Some(template)
          else None
        }
    }
  }

  private def canTemplateBeOverwritten(blockContext: TemplateRequestBlockContext,
                                       allowedIndices: Set[IndexName]): CheckContinuation[Set[Template]] = {
    logger.debug(s"[${blockContext.requestContext.id.show}] Checking - if existing template can be overwritten ...")
    val canAllTemplatesBeModified = blockContext.templates.forall { template =>
      val existingTemplate = blockContext
        .requestContext
        .allTemplates
        .find(_.name === template.name)
      existingTemplate match {
        case Some(t) => canTemplateBeChanged(t, allowedIndices)
        case None => true
      }
    }
    if (canAllTemplatesBeModified) continue
    else stop(CanPass.No())
  }

  private def canAddTemplateRequestPass(blockContext: TemplateRequestBlockContext,
                                        allowedIndices: Set[IndexName]): CheckContinuation[Set[Template]] = {
    if (blockContext.requestContext.action.isPutTemplate) {
      logger.debug(s"[${blockContext.requestContext.id.show}] Checking - if template can be added ...")
      val modifiedTemplates = blockContext
        .templates
        .flatMap { template =>
          val templatePatterns = template.patterns.toSet
          val narrowedPatterns = TemplateMatcher.narrowAllowedTemplateIndexPatterns(templatePatterns, allowedIndices)
          val narrowedOriginPatterns = narrowedPatterns.map(_._1)
          if (narrowedOriginPatterns == templatePatterns) {
            UniqueNonEmptyList
              .fromList(narrowedPatterns.map(_._2).toList)
              .map(nel => template.copy(patterns = nel))
          } else {
            logger.debug(
              s"""[${blockContext.requestContext.id.show}] Template ${template.name.show} cannot be added because
                 |it requires access to patterns [${templatePatterns.map(_.show).mkString(",")}], but according to this
                 |rule, there is only access for following ones [${narrowedOriginPatterns.map(_.show).mkString(",")}]
                 |""".stripMargin
            )
            None
          }
        }
      if (modifiedTemplates.size == blockContext.templates.size) {
        stop(CanPass.Yes(modifiedTemplates))
      } else {
        stop(CanPass.No())
      }
    } else {
      continue
    }
  }

  private def canTemplatesWriteRequestPass(blockContext: TemplateRequestBlockContext,
                                           allowedIndices: Set[IndexName]): CheckContinuation[Set[Template]] = {
    logger.debug(s"[${blockContext.requestContext.id.show}] Checking - write template request ...")
    val templates = blockContext.templates
    stop {
      templates.find(!canTemplateBeChanged(_, allowedIndices)) match {
        case Some(_) => CanPass.No()
        case None => CanPass.Yes(templates)
      }
    }
  }

  private def canTemplateBeChanged(template: Template, allowedIndices: Set[IndexName]) = {
    val templatePatterns = template.patterns.toSet
    val narrowedPatterns = TemplateMatcher.narrowAllowedTemplateIndexPatterns(templatePatterns, allowedIndices).map(_._2)
    narrowedPatterns.intersect(templatePatterns) == templatePatterns
  }

  private val matchAll = settings.allowedIndices.exists {
    case AlreadyResolved(indices) if indices.contains_(IndexName.`wildcard`) => true
    case _ => false
  }

  private type CheckContinuation[T] = Either[CanPass[T], Unit]
  private object IndicesCheckContinuation {
    def stop[T](result: CanPass[T]): CheckContinuation[T] = Left(result)

    def continue[T]: CheckContinuation[T] = Right(())
  }
}

object IndicesRule {
  val name = Rule.Name("indices")

  final case class Settings(allowedIndices: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]])

  private sealed trait CanPass[+T]
  private object CanPass {
    final case class Yes[T](value: T) extends CanPass[T]
    final case class No(reason: Option[No.Reason] = None) extends CanPass[Nothing]
    object No {
      def apply(reason: Reason): No = new No(Some(reason))

      sealed trait Reason
      object Reason {
        case object IndexNotExist extends Reason
      }
    }
  }
}
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
//package tech.beshu.ror.accesscontrol.blocks.rules.indicesrule
//
//import cats.implicits._
//import org.apache.logging.log4j.scala.Logging
//import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
//import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
//import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
//import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.{Fulfilled, Rejected}
//import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.domain.CanPass.No.Reason
//import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.domain.IndicesCheckContinuation._
//import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.domain._
//import tech.beshu.ror.accesscontrol.blocks.rules.utils.TemplatePatternsMatcher.filterAllowedTemplateIndexPatterns
//import tech.beshu.ror.accesscontrol.blocks.rules.utils.{IndicesMatcher, TemplatePatternsMatcher}
//import tech.beshu.ror.accesscontrol.domain.{IndexName, TemplateOperation}
//import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll
//import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList
//
//private [indicesrule] trait TemplateIndices {
//  this: Logging =>
//
//  protected def settings: IndicesRule.Settings
//
//  protected def processTemplateRequest2(blockContext: TemplateRequestBlockContext): RuleResult[TemplateRequestBlockContext] = {
//    val resolvedAllowedIndices = resolveAll(settings.allowedIndices.toNonEmptyList, blockContext).toSet
//
//    val canPass = if (blockContext.requestContext.isReadOnlyRequest) {
//      canTemplatesReadOnlyRequestPass(blockContext, resolvedAllowedIndices)
//    } else {
//      val result = for {
//        _ <- canTemplateBeOverwritten(blockContext, resolvedAllowedIndices)
//        _ <- canAddTemplateRequestPass(blockContext, resolvedAllowedIndices)
//        _ <- canTemplatesWriteRequestPass(blockContext, resolvedAllowedIndices)
//      } yield ()
//      result.left.getOrElse(CanPass.No())
//    }
//
//    canPass match {
//      case CanPass.Yes(templates) =>
//        Fulfilled(blockContext.withTemplates(templates))
//      case CanPass.No(Some(Reason.IndexNotExist)) =>
//        Rejected(Cause.IndexNotFound)
//      case CanPass.No(_) =>
//        Rejected()
//    }
//  }
//
//  private def canTemplatesReadOnlyRequestPass(blockContext: TemplateRequestBlockContext,
//                                              allowedIndices: Set[IndexName]): CanPass[Set[TemplateOperation]] = {
//    logger.debug(s"[${blockContext.requestContext.id.show}] Checking - template readonly request indices patterns ...")
//    CanPass.Yes {
//      blockContext
//        .templateOperations
//        .flatMap {
//          case template@IndexTemplate(_, patterns, _) =>
//            val filtered = filterAllowedTemplateIndexPatterns(patterns.toSet, allowedIndices).toList
//            if (filtered.nonEmpty) Some(template)
//            else None
//          case template: ComponentTemplate =>
//            ???
//            Option.empty[ComponentTemplate]
//        }
//    }
//  }
//
//  private def canTemplateBeOverwritten(blockContext: TemplateRequestBlockContext,
//                                       allowedIndices: Set[IndexName]): CheckContinuation[Set[TemplateOperation]] = {
//    logger.debug(s"[${blockContext.requestContext.id.show}] Checking - if existing template can be overwritten ...")
//    val canAllTemplatesBeModified = blockContext.templateOperations.forall { template =>
//      val existingTemplate = blockContext
//        .requestContext
//        .allTemplates
//        .find(_.name === template.name)
//      existingTemplate match {
//        case Some(t) => canTemplateBeChanged(t, allowedIndices)
//        case None => true
//      }
//    }
//    if (canAllTemplatesBeModified) continue
//    else stop(CanPass.No())
//  }
//
//  private sealed trait AddTemplateError
//  private object AddTemplateError {
//    sealed case class PatternError(narrowedOriginPatterns: Set[IndexName]) extends AddTemplateError
//    sealed case class AliasesError(forbiddenAliases: UniqueNonEmptyList[IndexName]) extends AddTemplateError
//  }
//
//  private def canAddTemplateRequestPass(blockContext: TemplateRequestBlockContext,
//                                        allowedIndices: Set[IndexName]): CheckContinuation[Set[TemplateOperation]] = {
//    if (blockContext.requestContext.action.isPutTemplate) {
//      logger.debug(s"[${blockContext.requestContext.id.show}] Checking - if template can be added ...")
//      val modifiedTemplates: Set[TemplateOperation] = blockContext
//        .templateOperations
//        .flatMap {
//          case template@IndexTemplate(_, patterns, aliases) =>
//            val result = for {
//              narrowedPatterns <- narrowTemplatePatterns(patterns, allowedIndices)
//              _ <- validateAliases(aliases, allowedIndices)
//            } yield {
//              if(patterns != narrowedPatterns) {
//                logger.debug(
//                  s"""[${blockContext.requestContext.id.show}] Template [${template.name.show}] can be allowed, but
//                     | indices patterns [${patterns.show}] have to be narrowed to [${narrowedPatterns.show}]""".oneLiner
//                )
//              }
//              template.copy(patterns = narrowedPatterns)
//            }
//
//            result match {
//              case Right(updatedTemplate) =>
//                Some(updatedTemplate)
//              case Left(AddTemplateError.PatternError(narrowedOriginPatterns)) =>
//                logger.debug(
//                  s"""[${blockContext.requestContext.id.show}] Template [${template.name.show}] cannot be added because
//                     | it requires access to patterns [${patterns.show}], but according to this rule, there is only
//                     | access for following ones [${narrowedOriginPatterns.show}]""".oneLiner
//                )
//                None
//              case Left(AddTemplateError.AliasesError(forbiddenAliases)) =>
//                logger.debug(
//                  s"""[${blockContext.requestContext.id.show}] Template [${template.name.show}] cannot be added because
//                     | it requires access to aliases [${aliases.show}], but according to this rule, following aliases are
//                     | forbidden [${forbiddenAliases.show}]""".oneLiner
//                )
//                None
//            }
//          case template@ComponentTemplate(_, aliases) =>
//            validateAliases(aliases, allowedIndices) match {
//              case Right(_) =>
//                Some(template)
//              case Left(AddTemplateError.AliasesError(forbiddenAliases)) =>
//                logger.debug(
//                  s"""[${blockContext.requestContext.id.show}] Template [${template.name.show}] cannot be added because
//                     | it requires access to aliases [${aliases.show}], but according to this rule, following aliases are
//                     | forbidden [${forbiddenAliases.show}]""".oneLiner
//                )
//                None
//            }
//        }
//      if (modifiedTemplates.size == blockContext.templateOperations.size) {
//        stop(CanPass.Yes(modifiedTemplates))
//      } else {
//        stop(CanPass.No())
//      }
//    } else {
//      continue
//    }
//  }
//
//  private def narrowTemplatePatterns(patterns: UniqueNonEmptyList[IndexName],
//                                     allowedIndices: Set[IndexName]): Either[AddTemplateError.PatternError, UniqueNonEmptyList[IndexName]] = {
//    val templatePatterns = patterns.toSet
//    val narrowedPatterns = TemplatePatternsMatcher.narrowAllowedTemplateIndexPatterns(templatePatterns, allowedIndices)
//    val narrowedOriginPatterns = narrowedPatterns.map(_._1)
//    if (narrowedOriginPatterns == templatePatterns) {
//      UniqueNonEmptyList
//        .fromList(narrowedPatterns.map(_._2).toList)
//        .toRight(AddTemplateError.PatternError(Set.empty))
//    } else {
//      Left(AddTemplateError.PatternError(narrowedOriginPatterns))
//    }
//  }
//
//  private def validateAliases(requestedAliases: Set[IndexName],
//                              ruleAllowedIndices: Set[IndexName]): Either[AddTemplateError.AliasesError, Unit] = {
//    UniqueNonEmptyList.fromSet(requestedAliases) match {
//      case None => Right(())
//      case Some(_) =>
//        val allowedRequestedAliases = IndicesMatcher.create(ruleAllowedIndices).filterIndices(requestedAliases)
//        if (allowedRequestedAliases == requestedAliases) Right(())
//        else {
//          val forbiddenRequestedAliases = requestedAliases.diff(allowedRequestedAliases)
//          Left(AddTemplateError.AliasesError(UniqueNonEmptyList.unsafeFromSet(forbiddenRequestedAliases)))
//        }
//    }
//  }
//
//  private def canTemplatesWriteRequestPass(blockContext: TemplateRequestBlockContext,
//                                           allowedIndices: Set[IndexName]): CheckContinuation[Set[TemplateOperation]] = {
//    logger.debug(s"[${blockContext.requestContext.id.show}] Checking - write template request ...")
//    val templates = blockContext.templateOperations
//    stop {
//      templates.find(!canTemplateBeChanged(_, allowedIndices)) match {
//        case Some(_) => CanPass.No()
//        case None => CanPass.Yes(templates)
//      }
//    }
//  }
//
//  private def canTemplateBeChanged(template: TemplateOperation, allowedIndices: Set[IndexName]) = {
//    val templatePatternsAndAliases = template match {
//      case IndexTemplate(_, patterns, aliases) => patterns.toSet ++ aliases
//      case ComponentTemplate(_, aliases) => aliases
//    }
//    val narrowedPatterns = TemplatePatternsMatcher.narrowAllowedTemplateIndexPatterns(templatePatternsAndAliases, allowedIndices).map(_._2)
//    narrowedPatterns.intersect(templatePatternsAndAliases) == templatePatternsAndAliases
//  }
//
//}

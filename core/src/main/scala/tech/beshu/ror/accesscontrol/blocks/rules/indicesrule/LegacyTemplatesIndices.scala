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

import cats.data.NonEmptyList
import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.resultBasedOnCondition
import tech.beshu.ror.accesscontrol.blocks.rules.utils.TemplateMatcher
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.implicits._
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

private[indicesrule] trait LegacyTemplatesIndices
  extends Logging {
  this: AllTemplateIndices =>

  protected def gettingLegacyTemplates(templateNamePatterns: NonEmptyList[TemplateNamePattern])
                                      (implicit blockContext: TemplateRequestBlockContext,
                                       allowedIndices: AllowedIndices): RuleResult[TemplateRequestBlockContext] = {
    logger.debug(
      s"""[${blockContext.requestContext.id.show}] * getting Templates for name patterns [${templateNamePatterns.show}] ...""".oneLiner
    )
    val existingTemplates = findTemplatesBy(templateNamePatterns.toList.toSet, in = blockContext)
    if (existingTemplates.isEmpty) {
      logger.debug(
        s"""[${blockContext.requestContext.id.show}] * no Templates for name patterns [${templateNamePatterns.show}] found ..."""
      )
      RuleResult.fulfilled(blockContext)
    } else {
      val filteredExistingTemplates = existingTemplates.filter(canViewExistingTemplate).toList
      NonEmptyList.fromList(filteredExistingTemplates) match {
        case Some(nonEmptyFilterTemplates) =>
          val templateNamePatterns = nonEmptyFilterTemplates.map(t => TemplateNamePattern.from(t.name))
          val modifiedOperation = TemplateOperation.GettingLegacyTemplates(templateNamePatterns)
          RuleResult.fulfilled(
            blockContext
              .withTemplateOperation(modifiedOperation)
              .withResponseTemplateTransformation(filterTemplatesNotAllowedPatterns)
          )
        case None =>
          RuleResult.rejected()
      }
    }
  }

  protected def addingLegacyTemplate(newTemplateName: TemplateName,
                                     newTemplateIndicesPatterns: UniqueNonEmptyList[IndexName])
                                    (implicit blockContext: TemplateRequestBlockContext,
                                     allowedIndices: AllowedIndices): RuleResult[TemplateRequestBlockContext] = {
    logger.debug(
      s"""[${blockContext.requestContext.id.show}] * adding Template [${newTemplateName.show}] with index
         | patterns [${newTemplateIndicesPatterns.show}] ...""".oneLiner
    )
    findTemplateBy(name = newTemplateName, in = blockContext) match {
      case Some(existingTemplate) =>
        logger.debug(
          s"""[${blockContext.requestContext.id.show}] * Template with name [${existingTemplate.name.show}]
             | (indices patterns [${existingTemplate.patterns.show}]) exits ...""".oneLiner
        )
        resultBasedOnCondition(blockContext) {
          canModifyExistingTemplate(existingTemplate) &&
            canAddNewLegacyTemplate(newTemplateName, newTemplateIndicesPatterns)
        }
      case None =>
        resultBasedOnCondition(blockContext) {
          canAddNewLegacyTemplate(newTemplateName, newTemplateIndicesPatterns)
        }
    }
  }

  protected def deletingLegacyTemplates(templateNamePatterns: NonEmptyList[TemplateNamePattern])
                                       (implicit blockContext: TemplateRequestBlockContext,
                                        allowedIndices: AllowedIndices): RuleResult[TemplateRequestBlockContext] = {
    logger.debug(
      s"""[${blockContext.requestContext.id.show}] * deleting Templates with name patterns [${templateNamePatterns.show}] ..."""
    )
    val result = templateNamePatterns.foldLeft(List.empty[TemplateNamePattern].asRight[Unit]) {
      case (Right(acc), templateNamePattern) =>
        deletingLegacyTemplate(templateNamePattern) match {
          case Result.Allowed(t) => Right(t :: acc)
          case Result.NotFound(t) => Right(generateRorArtificialName(t) :: acc)
          case Result.Forbidden(_) => Left(())
        }
      case (rejected@Left(_), _) => rejected
    }
    result match {
      case Left(_) | Right(Nil) =>
        RuleResult.rejected()
      case Right(nonEmptyPatternsList) =>
        val modifiedOperation = TemplateOperation.DeletingLegacyTemplates(NonEmptyList.fromListUnsafe(nonEmptyPatternsList))
        RuleResult.fulfilled(blockContext.withTemplateOperation(modifiedOperation))
    }
  }

  private def deletingLegacyTemplate(templateNamePattern: TemplateNamePattern)
                                    (implicit blockContext: TemplateRequestBlockContext,
                                     allowedIndices: AllowedIndices): PartialResult[TemplateNamePattern] = {
    val foundTemplates = findTemplatesBy(namePattern = templateNamePattern, in = blockContext)
    if (foundTemplates.isEmpty) {
      logger.debug(
        s"""[${blockContext.requestContext.id.show}] * no Templates for name pattern [${templateNamePattern.show}] found ..."""
      )
      Result.NotFound(templateNamePattern)
    } else {
      logger.debug(
        s"""[${blockContext.requestContext.id.show}] * checking if Templates with names [${foundTemplates.map(_.name).show}] can be removed ..."""
      )
      if (foundTemplates.forall(canModifyExistingTemplate)) Result.Allowed(templateNamePattern)
      else Result.Forbidden(templateNamePattern)
    }
  }

  private def canAddNewLegacyTemplate(newTemplateName: TemplateName,
                                      newTemplateIndicesPatterns: UniqueNonEmptyList[IndexName])
                                     (implicit blockContext: TemplateRequestBlockContext,
                                      allowedIndices: AllowedIndices) = {
    logger.debug(
      s"""[${blockContext.requestContext.id.show}] * checking if Template [${newTemplateName.show}] can be added ..."""
    )
    newTemplateIndicesPatterns.toList
      .forall { pattern =>
        val isPatternAllowed = allowedIndices.resolved.exists(_.matches(pattern))
        if (!isPatternAllowed) logger.debug(
          s"""[${blockContext.requestContext.id.show}] STOP: one of Template's [${newTemplateName.show}]
             | index pattern [${pattern.show}] is forbidden.""".oneLiner
        )
        isPatternAllowed
      }
  }

  private def canViewExistingTemplate(existingTemplate: Template.LegacyTemplate)
                                     (implicit blockContext: TemplateRequestBlockContext,
                                      allowedIndices: AllowedIndices) = {
    val isTemplateForbidden = existingTemplate.patterns.toList
      .forall { pattern =>
        allowedIndices.resolved.forall(i => !i.matches(pattern))
      }
    if (isTemplateForbidden) logger.debug(
      s"""[${blockContext.requestContext.id.show}] WARN: Template [${existingTemplate.name.show}] is forbidden
         | because none of its index patterns is allowed by the rule""".oneLiner
    )
    !isTemplateForbidden
  }

  private def canModifyExistingTemplate(existingTemplate: Template.LegacyTemplate)
                                       (implicit blockContext: TemplateRequestBlockContext,
                                        allowedIndices: AllowedIndices) = {
    logger.debug(
      s"[${blockContext.requestContext.id.show}] * checking if Template [${existingTemplate.name.show}] can be modified by the user ..."
    )
    existingTemplate.patterns.toList
      .forall { pattern =>
        val isPatternAllowed = allowedIndices.resolved.exists(_.matches(pattern))
        if (!isPatternAllowed) logger.debug(
          s"""[${blockContext.requestContext.id.show}] STOP: cannot allow to modify existing Template
             | [${existingTemplate.name.show}], because its index pattern [${pattern.show}] is not allowed by rule
             | (it means that user has no access to it)""".oneLiner
        )
        isPatternAllowed
      }
  }

  private def findTemplateBy(name: TemplateName, in: TemplateRequestBlockContext) = {
    in.requestContext.legacyTemplates.find(_.name == name)
  }

  private def findTemplatesBy(namePattern: TemplateNamePattern, in: TemplateRequestBlockContext): Set[Template.LegacyTemplate] = {
    findTemplatesBy(Set(namePattern), in)
  }

  private def findTemplatesBy(namePatterns: Set[TemplateNamePattern], in: TemplateRequestBlockContext): Set[Template.LegacyTemplate] = {
    new TemplateMatcher(namePatterns).filterTemplates(in.requestContext.legacyTemplates)
  }

  private def filterTemplatesNotAllowedPatterns(templates: Set[Template])
                                               (implicit blockContext: TemplateRequestBlockContext,
                                                allowedIndices: AllowedIndices): Set[Template] = {
    templates.flatMap {
      case Template.LegacyTemplate(name, patterns) =>
        val onlyAllowedPatterns = patterns.filter(p => allowedIndices.resolved.exists(_.matches(p)))
        UniqueNonEmptyList.fromSortedSet(onlyAllowedPatterns) match {
          case Some(nonEmptyAllowedPatterns) =>
            Set[Template](Template.LegacyTemplate(name, nonEmptyAllowedPatterns))
          case None =>
            logger.error(
              s"""[${blockContext.requestContext.id.show} Template [${name.show}] has no allowed patterns, even if
                 | it was allowed to be returned by ES. Please, report it as soon as possible!""".oneLiner
            )
            Set.empty[Template]
        }
      case other =>
        Set[Template](other)
    }
  }
}

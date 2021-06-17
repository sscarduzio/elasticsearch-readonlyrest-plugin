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
package tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.templates

import cats.implicits._
import cats.data.NonEmptyList
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext.TemplatesTransformation
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.resultBasedOnCondition
import tech.beshu.ror.accesscontrol.domain.TemplateOperation.GettingLegacyTemplates
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.utils.ScalaOps._

private[indicesrule] trait LegacyTemplatesIndices
  extends Logging {
  this: AllTemplateIndices =>

  protected def gettingLegacyTemplates(templateNamePatterns: NonEmptyList[TemplateNamePattern])
                                      (implicit blockContext: TemplateRequestBlockContext,
                                       allowedIndices: AllowedIndices): RuleResult[TemplateRequestBlockContext] = {
    processGettingLegacyTemplates(templateNamePatterns) match {
      case Right((operation, transformation)) =>
        RuleResult.fulfilled(
          blockContext
            .withTemplateOperation(operation)
            .withResponseTemplateTransformation(transformation)
        )
      case Left(cause) =>
        RuleResult.rejected(Some(cause))
    }
  }

  protected def processGettingLegacyTemplates(templateNamePatterns: NonEmptyList[TemplateNamePattern])
                                             (implicit blockContext: TemplateRequestBlockContext,
                                              allowedIndices: AllowedIndices): Either[Cause, (GettingLegacyTemplates, TemplatesTransformation)] = {
    logger.debug(
      s"""[${blockContext.requestContext.id.show}] * getting Templates for name patterns [${templateNamePatterns.show}] ...""".oneLiner
    )
    val existingTemplates = findTemplatesBy(templateNamePatterns.toList.toSet, in = blockContext)
    if (existingTemplates.isEmpty) {
      logger.debug(
        s"""[${blockContext.requestContext.id.show}] * no Templates for name patterns [${templateNamePatterns.show}] found ..."""
      )
      Right((TemplateOperation.GettingLegacyTemplates(templateNamePatterns), identity))
    } else {
      val filteredExistingTemplates = existingTemplates.filter(canViewExistingTemplate).toList
      NonEmptyList.fromList(filteredExistingTemplates) match {
        case Some(nonEmptyFilterTemplates) =>
          val namePatterns = nonEmptyFilterTemplates.map(t => TemplateNamePattern.from(t.name))
          val modifiedOperation = TemplateOperation.GettingLegacyTemplates(namePatterns)
          Right((modifiedOperation, filterTemplatesNotAllowedPatternsAndAliases(_)))
        case None =>
          Left(Cause.TemplateNotFound)
      }
    }
  }

  protected def addingLegacyTemplate(newTemplateName: TemplateName,
                                     newTemplateIndicesPatterns: UniqueNonEmptyList[IndexPattern],
                                     aliases: Set[ClusterIndexName])
                                    (implicit blockContext: TemplateRequestBlockContext,
                                     allowedIndices: AllowedIndices): RuleResult[TemplateRequestBlockContext] = {
    logger.debug(
      s"""[${blockContext.requestContext.id.show}] * adding Template [${newTemplateName.show}] with index
         | patterns [${newTemplateIndicesPatterns.show}] and aliases [${aliases.show}] ...""".oneLiner
    )
    findTemplateBy(name = newTemplateName, in = blockContext) match {
      case Some(existingTemplate) =>
        logger.debug(
          s"""[${blockContext.requestContext.id.show}] * Template with name [${existingTemplate.name.show}]
             | (indices patterns [${existingTemplate.patterns.show}]) exits ...""".oneLiner
        )
        resultBasedOnCondition(blockContext) {
          canModifyExistingTemplate(existingTemplate) &&
            canAddNewLegacyTemplate(newTemplateName, newTemplateIndicesPatterns, aliases)
        }
      case None =>
        resultBasedOnCondition(blockContext) {
          canAddNewLegacyTemplate(newTemplateName, newTemplateIndicesPatterns, aliases)
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
          case Result.Allowed(t) =>
            Right(t :: acc)
          case Result.NotFound(t) =>
            implicit val _ = identifierGenerator
            val nonExistentTemplateNamePattern = TemplateNamePattern.generateNonExistentBasedOn(t)
            Right(nonExistentTemplateNamePattern :: acc)
          case Result.Forbidden(_) =>
            Left(())
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
                                      newTemplateIndicesPatterns: UniqueNonEmptyList[IndexPattern],
                                      newTemplateAliases: Set[ClusterIndexName])
                                     (implicit blockContext: TemplateRequestBlockContext,
                                      allowedIndices: AllowedIndices) = {
    logger.debug(
      s"""[${blockContext.requestContext.id.show}] * checking if Template [${newTemplateName.show}] with indices
         | patterns [${newTemplateIndicesPatterns.show}] and aliases [${newTemplateAliases.show}] can be added ...""".oneLiner
    )
    lazy val allPatternAllowed =
      newTemplateIndicesPatterns.toList
        .forall { pattern =>
          val isPatternAllowed = allowedIndices.resolved.exists(pattern.isSubsetOf)
          if (!isPatternAllowed) logger.debug(
            s"""[${blockContext.requestContext.id.show}] STOP: one of Template's [${newTemplateName.show}]
               | index pattern [${pattern.show}] is forbidden.""".oneLiner
          )
          isPatternAllowed
        }

    lazy val allAliasesAllowed =
      if (newTemplateAliases.isEmpty) true
      else {
        newTemplateAliases.forall { alias =>
          val allowed = isAliasAllowed(alias)
          if (!allowed) logger.debug(
            s"""[${blockContext.requestContext.id.show}] STOP: one of Template's [${newTemplateName.show}]
               | alias [${alias.show}] is forbidden.""".oneLiner
          )
          allowed
        }
      }
    allPatternAllowed && allAliasesAllowed
  }

  private def canViewExistingTemplate(existingTemplate: Template.LegacyTemplate)
                                     (implicit blockContext: TemplateRequestBlockContext,
                                      allowedIndices: AllowedIndices) = {
    val isTemplateAllowed = existingTemplate.patterns.toList
      .exists { pattern => pattern.isAllowedByAny(allowedIndices.resolved) }
    if (!isTemplateAllowed) logger.debug(
      s"""[${blockContext.requestContext.id.show}] WARN: Template [${existingTemplate.name.show}] is forbidden
         | because none of its index patterns [${existingTemplate.patterns.show}] is allowed by the rule""".oneLiner
    )
    isTemplateAllowed
  }

  private def canModifyExistingTemplate(existingTemplate: Template.LegacyTemplate)
                                       (implicit blockContext: TemplateRequestBlockContext,
                                        allowedIndices: AllowedIndices) = {
    logger.debug(
      s"[${blockContext.requestContext.id.show}] * checking if Template [${existingTemplate.name.show}] with indices patterns" +
        s" [${existingTemplate.patterns.show}] can be modified by the user ..."
    )
    lazy val allPatternAllowed =
      existingTemplate.patterns.toList
        .forall { pattern =>
          val isPatternAllowed = allowedIndices.resolved.exists(pattern.isSubsetOf)
          if (!isPatternAllowed) logger.debug(
            s"""[${blockContext.requestContext.id.show}] STOP: cannot allow to modify existing Template
               | [${existingTemplate.name.show}], because its index pattern [${pattern.show}] is not allowed by rule
               | (it means that user has no access to it)""".oneLiner
          )
          isPatternAllowed
        }

    lazy val allAliasesAllowed =
      if (existingTemplate.aliases.isEmpty) true
      else {
        existingTemplate.aliases.forall { alias =>
          val allowed = isAliasAllowed(alias)
          if (!allowed) logger.debug(
            s"""[${blockContext.requestContext.id.show}] STOP: cannot allow to modify existing Template
               | [${existingTemplate.name.show}], because its alias [${alias.show}] is not allowed by rule
               | (it means that user has no access to it)""".oneLiner
          )
          allowed
        }
      }
    allPatternAllowed && allAliasesAllowed
  }

  private def findTemplateBy(name: TemplateName, in: TemplateRequestBlockContext) = {
    in.requestContext.legacyTemplates.find(_.name == name)
  }

  private def findTemplatesBy(namePattern: TemplateNamePattern, in: TemplateRequestBlockContext): Set[Template.LegacyTemplate] = {
    findTemplatesBy(Set(namePattern), in)
  }

  private def findTemplatesBy(namePatterns: Set[TemplateNamePattern], in: TemplateRequestBlockContext): Set[Template.LegacyTemplate] = {
    filterTemplates(namePatterns, in.requestContext.legacyTemplates)
  }

  private def filterTemplatesNotAllowedPatternsAndAliases(templates: Set[Template])
                                                         (implicit blockContext: TemplateRequestBlockContext,
                                                          allowedIndices: AllowedIndices): Set[Template] = {
    templates.flatMap {
      case Template.LegacyTemplate(name, patterns, aliases) =>
        val onlyAllowedPatterns = patterns.filter(p => p.isAllowedByAny(allowedIndices.resolved))
        val onlyAllowedAliases = aliases.filter(isAliasAllowed)
        UniqueNonEmptyList.fromSortedSet(onlyAllowedPatterns) match {
          case Some(nonEmptyAllowedPatterns) =>
            Set[Template](Template.LegacyTemplate(name, nonEmptyAllowedPatterns, onlyAllowedAliases))
          case None =>
            Set.empty[Template]
        }
      case other =>
        Set[Template](other)
    }
  }
}

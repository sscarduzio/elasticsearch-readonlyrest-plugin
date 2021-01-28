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
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.resultBasedOnCondition
import tech.beshu.ror.accesscontrol.blocks.rules.utils.TemplateMatcher
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.implicits._
import tech.beshu.ror.utils.ScalaOps._
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

private[indicesrule] trait IndexTemplateIndices
  extends Logging {
  this: AllTemplateIndices =>

  protected def gettingIndexTemplates(templateNamePatterns: NonEmptyList[TemplateNamePattern])
                                     (implicit blockContext: TemplateRequestBlockContext,
                                      allowedIndices: AllowedIndices): RuleResult[TemplateRequestBlockContext] = {
    logger.debug(
      s"""[${blockContext.requestContext.id.show}] * getting Index Templates for name patterns [${templateNamePatterns.show}] ...""".oneLiner
    )
    val existingTemplates = findTemplatesBy(templateNamePatterns.toList.toSet, in = blockContext)
    if (existingTemplates.isEmpty) {
      logger.debug(
        s"""[${blockContext.requestContext.id.show}] * no Index Templates for name patterns [${templateNamePatterns.show}] found ..."""
      )
      RuleResult.fulfilled(blockContext)
    } else {
      val filteredExistingTemplates = existingTemplates.filter(canViewExistingTemplate).toList
      NonEmptyList.fromList(filteredExistingTemplates) match {
        case Some(nonEmptyFilterTemplates) =>
          val templateNamePatterns = nonEmptyFilterTemplates.map(t => TemplateNamePattern.from(t.name))
          val modifiedOperation = TemplateOperation.GettingIndexTemplates(templateNamePatterns)
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

  protected def addingIndexTemplate(newTemplateName: TemplateName,
                                    newTemplateIndicesPatterns: UniqueNonEmptyList[IndexName],
                                    aliases: Set[IndexName])
                                   (implicit blockContext: TemplateRequestBlockContext,
                                    allowedIndices: AllowedIndices): RuleResult[TemplateRequestBlockContext] = {
    logger.debug(
      s"""[${blockContext.requestContext.id.show}] * adding Index Template [${newTemplateName.show}] with index
         | patterns [${newTemplateIndicesPatterns.show}] ...""".oneLiner
    )
    findTemplateBy(name = newTemplateName, in = blockContext) match {
      case Some(existingTemplate) =>
        logger.debug(
          s"""[${blockContext.requestContext.id.show}] * Index Template with name [${existingTemplate.name.show}]
             | (indices patterns [${existingTemplate.patterns.show}]) exits ...""".oneLiner
        )
        resultBasedOnCondition(blockContext) {
          canModifyExistingIndexTemplate(existingTemplate) &&
            canAddNewIndexTemplate(newTemplateName, newTemplateIndicesPatterns, aliases)
        }
      case None =>
        resultBasedOnCondition(blockContext) {
          canAddNewIndexTemplate(newTemplateName, newTemplateIndicesPatterns, aliases)
        }
    }
  }

  protected def deletingIndexTemplates(templateNamePatterns: NonEmptyList[TemplateNamePattern])
                                      (implicit blockContext: TemplateRequestBlockContext,
                                       allowedIndices: AllowedIndices): RuleResult[TemplateRequestBlockContext] = {
    logger.debug(
      s"""[${blockContext.requestContext.id.show}] * deleting Index Templates with name patterns [${templateNamePatterns.show}] ..."""
    )
    val result = templateNamePatterns.foldLeft(List.empty[TemplateNamePattern].asRight[Unit]) {
      case (Right(acc), templateNamePattern) =>
        deletingIndexTemplate(templateNamePattern) match {
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
        val modifiedOperation = TemplateOperation.DeletingIndexTemplates(NonEmptyList.fromListUnsafe(nonEmptyPatternsList))
        RuleResult.fulfilled(blockContext.withTemplateOperation(modifiedOperation))
    }
  }

  private def deletingIndexTemplate(templateNamePattern: TemplateNamePattern)
                                   (implicit blockContext: TemplateRequestBlockContext,
                                    allowedIndices: AllowedIndices): PartialResult[TemplateNamePattern] = {
    val foundTemplates = findTemplatesBy(namePattern = templateNamePattern, in = blockContext)
    if (foundTemplates.isEmpty) {
      logger.debug(
        s"""[${blockContext.requestContext.id.show}] * no Index Templates for name pattern [${templateNamePattern.show}] found ..."""
      )
      Result.NotFound(templateNamePattern)
    } else {
      logger.debug(
        s"""[${blockContext.requestContext.id.show}] * checking if Index Templates with names [${foundTemplates.map(_.name).show}] can be removed ..."""
      )
      if (foundTemplates.forall(canModifyExistingIndexTemplate)) Result.Allowed(templateNamePattern)
      else Result.Forbidden(templateNamePattern)
    }
  }

  private def canAddNewIndexTemplate(newTemplateName: TemplateName,
                                     newTemplateIndicesPatterns: UniqueNonEmptyList[IndexName],
                                     newTemplateAliases: Set[IndexName])
                                    (implicit blockContext: TemplateRequestBlockContext,
                                     allowedIndices: AllowedIndices) = {
    logger.debug(
      s"""[${blockContext.requestContext.id.show}] * checking if Index Template [${newTemplateName.show}] can be added ..."""
    )
    lazy val allPatternAllowed = newTemplateIndicesPatterns.toList
      .forall { pattern =>
        val isPatternAllowed = allowedIndices.resolved.exists(_.matches(pattern))
        if (!isPatternAllowed) logger.debug(
          s"""[${blockContext.requestContext.id.show}] STOP: one of Template's [${newTemplateName.show}]
             | index pattern [${pattern.show}] is forbidden.""".oneLiner
        )
        isPatternAllowed
      }
    lazy val allAliasesAllowed =
      if(newTemplateAliases.isEmpty) true
      else {
        newTemplateAliases.forall { alias =>
          val isAliasAllowed = alias match {
            case Placeholder(placeholder) =>
              val potentialAliases = allowedIndices.resolved.map(i => placeholder.index(i.value))
              potentialAliases.exists { alias => allowedIndices.resolved.exists(_.matches(alias)) }
            case _ =>
              allowedIndices.resolved.exists(_.matches(alias))
          }
          if (!isAliasAllowed) logger.debug(
            s"""[${blockContext.requestContext.id.show}] STOP: one of Template's [${newTemplateName.show}]
               | alias [${alias.show}] is forbidden.""".oneLiner
          )
          isAliasAllowed
        }
      }
    allPatternAllowed && allAliasesAllowed
  }

  private def canViewExistingTemplate(existingTemplate: Template.IndexTemplate)
                                     (implicit blockContext: TemplateRequestBlockContext,
                                      allowedIndices: AllowedIndices) = {
    val isTemplateForbidden = existingTemplate.patterns.toList
      .forall { pattern =>
        allowedIndices.resolved.forall(i => !i.matches(pattern))
      }
    if (isTemplateForbidden) logger.debug(
      s"""[${blockContext.requestContext.id.show}] WARN: Index Template [${existingTemplate.name.show}] is forbidden
         | because none of its index patterns is allowed by the rule""".oneLiner
    )
    !isTemplateForbidden
  }

  private def canModifyExistingIndexTemplate(existingTemplate: Template.IndexTemplate)
                                            (implicit blockContext: TemplateRequestBlockContext,
                                             allowedIndices: AllowedIndices) = {
    logger.debug(
      s"[${blockContext.requestContext.id.show}] * checking if Index Template [${existingTemplate.name.show}] can be modified by the user ..."
    )
    lazy val allPatternAllowed = existingTemplate.patterns.toList
      .forall { pattern =>
        val isPatternAllowed = allowedIndices.resolved.exists(_.matches(pattern))
        if (!isPatternAllowed) logger.debug(
          s"""[${blockContext.requestContext.id.show}] STOP: cannot allow to modify existing Index Template
             | [${existingTemplate.name.show}], because its index pattern [${pattern.show}] is not allowed by rule
             | (it means that user has no access to it)""".oneLiner
        )
        isPatternAllowed
      }
    lazy val allAliasesAllowed =
      if(existingTemplate.aliases.isEmpty) true
      else {
        existingTemplate.aliases.forall { alias =>
          val isAliasAllowed = alias match {
            case Placeholder(placeholder) =>
              val potentialAliases = allowedIndices.resolved.map(i => placeholder.index(i.value))
              potentialAliases.exists { alias => allowedIndices.resolved.exists(_.matches(alias)) }
            case _ =>
              allowedIndices.resolved.exists(_.matches(alias))
          }
          if (!isAliasAllowed) logger.debug(
            s"""[${blockContext.requestContext.id.show}] STOP: cannot allow to modify existing Index Template
               | [${existingTemplate.name.show}], because its alias [${alias.show}] is not allowed by rule
               | (it means that user has no access to it)""".oneLiner
          )
          isAliasAllowed
        }
      }
    allPatternAllowed && allAliasesAllowed
  }

  private def findTemplateBy(name: TemplateName, in: TemplateRequestBlockContext) = {
    in.requestContext.indexTemplates.find(_.name == name)
  }

  private def findTemplatesBy(namePattern: TemplateNamePattern, in: TemplateRequestBlockContext): Set[Template.IndexTemplate] = {
    findTemplatesBy(Set(namePattern), in)
  }

  private def findTemplatesBy(namePatterns: Set[TemplateNamePattern], in: TemplateRequestBlockContext): Set[Template.IndexTemplate] = {
    new TemplateMatcher(namePatterns).filterTemplates(in.requestContext.indexTemplates)
  }

  private def filterTemplatesNotAllowedPatterns(templates: Set[Template])
                                               (implicit blockContext: TemplateRequestBlockContext,
                                                allowedIndices: AllowedIndices): Set[Template] = {
    templates.flatMap {
      case Template.IndexTemplate(name, patterns, aliases) =>
        val onlyAllowedPatterns = patterns.filter(p => allowedIndices.resolved.exists(_.matches(p)))
        val onlyAllowedAliases = aliases.filter(a => allowedIndices.resolved.exists(_.matches(a))) // todo: {index} placeholder handling
        UniqueNonEmptyList.fromSortedSet(onlyAllowedPatterns) match {
          case Some(nonEmptyAllowedPatterns) =>
            Set[Template](Template.IndexTemplate(name, nonEmptyAllowedPatterns, onlyAllowedAliases))
          case None =>
            logger.error(
              s"""[${blockContext.requestContext.id.show} Index Template [${name.show}] has no allowed patterns, even if
                 | it was allowed to be returned by ES. Please, report it as soon as possible!""".oneLiner
            )
            Set.empty[Template]
        }
      case other =>
        Set[Template](other)
    }
  }
}

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
package tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.templates

import cats.data.NonEmptyList
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.resultBasedOnCondition
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*

private[indices] trait ComponentTemplateIndices
  extends Logging {
  this: AllTemplateIndices =>

  protected def gettingComponentTemplates(templateNamePatterns: NonEmptyList[TemplateNamePattern])
                                         (implicit blockContext: TemplateRequestBlockContext,
                                          allowedIndices: AllowedIndices): RuleResult[TemplateRequestBlockContext] = {
    logger.debug(
      s"""[${blockContext.requestContext.id.show}] * getting Component Templates for name patterns [${templateNamePatterns.show}] ...""".oneLiner
    )
    val existingTemplates = findTemplatesBy(templateNamePatterns.toList.toSet, in = blockContext)
    if (existingTemplates.isEmpty) {
      logger.debug(
        s"""[${blockContext.requestContext.id.show}] * no Component Templates for name patterns [${templateNamePatterns.show}] found ..."""
      )
      RuleResult.fulfilled(blockContext)
    } else {
      val operation = TemplateOperation.GettingComponentTemplates(templateNamePatterns)
      RuleResult.fulfilled(
        blockContext
          .withTemplateOperation(operation)
          .withResponseTemplateTransformation(filterTemplatesNotAllowedAliases)
      )
    }
  }

  protected def addingComponentTemplate(newTemplateName: TemplateName,
                                        aliases: Set[RequestedIndex[ClusterIndexName]])
                                       (implicit blockContext: TemplateRequestBlockContext,
                                        allowedIndices: AllowedIndices): RuleResult[TemplateRequestBlockContext] = {
    logger.debug(
      s"""[${blockContext.requestContext.id.show}] * adding Component Template [${newTemplateName.show}] with aliases
         | [${aliases.show}] ...""".oneLiner
    )
    findTemplateBy(name = newTemplateName, in = blockContext) match {
      case Some(existingTemplate) =>
        logger.debug(
          s"""[${blockContext.requestContext.id.show}] * Component Template with name [${existingTemplate.name.show}] exits ...""".oneLiner
        )
        resultBasedOnCondition(blockContext) {
          canModifyExistingComponentTemplate(existingTemplate) &&
            canAddNewComponentTemplate(newTemplateName, aliases)
        }
      case None =>
        resultBasedOnCondition(blockContext) {
          canAddNewComponentTemplate(newTemplateName, aliases)
        }
    }
  }

  protected def deletingComponentTemplates(templateNamePatterns: NonEmptyList[TemplateNamePattern])
                                          (implicit blockContext: TemplateRequestBlockContext,
                                           allowedIndices: AllowedIndices): RuleResult[TemplateRequestBlockContext] = {
    logger.debug(
      s"""[${blockContext.requestContext.id.show}] * deleting Component Templates with name patterns [${templateNamePatterns.show}] ..."""
    )
    val result = templateNamePatterns.foldLeft(List.empty[TemplateNamePattern].asRight[Unit]) {
      case (Right(acc), templateNamePattern) =>
        deletingComponentTemplate(templateNamePattern) match {
          case Result.Allowed(t) =>
            Right(t :: acc)
          case Result.NotFound(t) =>
            implicit val _generator = identifierGenerator
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
        val modifiedOperation = TemplateOperation.DeletingComponentTemplates(NonEmptyList.fromListUnsafe(nonEmptyPatternsList))
        RuleResult.fulfilled(blockContext.withTemplateOperation(modifiedOperation))
    }
  }

  private def deletingComponentTemplate(templateNamePattern: TemplateNamePattern)
                                       (implicit blockContext: TemplateRequestBlockContext,
                                        allowedIndices: AllowedIndices): PartialResult[TemplateNamePattern] = {
    val foundTemplates = findTemplatesBy(namePattern = templateNamePattern, in = blockContext)
    if (foundTemplates.isEmpty) {
      logger.debug(
        s"""[${blockContext.requestContext.id.show}] * no Component Templates for name pattern [${templateNamePattern.show}] found ..."""
      )
      Result.NotFound(templateNamePattern)
    } else {
      logger.debug(
        s"""[${blockContext.requestContext.id.show}] * checking if Component Templates with names [${foundTemplates.map(_.name).show}] can be removed ..."""
      )
      if (foundTemplates.forall(canModifyExistingComponentTemplate)) Result.Allowed(templateNamePattern)
      else Result.Forbidden(templateNamePattern)
    }
  }

  private def canAddNewComponentTemplate(newTemplateName: TemplateName,
                                         newTemplateAliases: Iterable[RequestedIndex[ClusterIndexName]])
                                        (implicit blockContext: TemplateRequestBlockContext,
                                         allowedIndices: AllowedIndices) = {
    logger.debug(
      s"""[${blockContext.requestContext.id.show}] * checking if Component Template [${newTemplateName.show}] can be added ..."""
    )
    lazy val allAliasesAllowed =
      if (newTemplateAliases.isEmpty) true
      else {
        newTemplateAliases.forall { alias =>
          val allowed = isAliasAllowed(alias.name)
          if (!allowed) logger.debug(
            s"""[${blockContext.requestContext.id.show}] STOP: one of Template's [${newTemplateName.show}]
               | alias [${alias.show}] is forbidden.""".oneLiner
          )
          allowed
        }
      }
    allAliasesAllowed
  }

  private def canModifyExistingComponentTemplate(existingTemplate: Template.ComponentTemplate)
                                                (implicit blockContext: TemplateRequestBlockContext,
                                                 allowedIndices: AllowedIndices) = {
    logger.debug(
      s"[${blockContext.requestContext.id.show}] * checking if Component Template [${existingTemplate.name.show}] can be modified by the user ..."
    )
    lazy val allAliasesAllowed =
      if (existingTemplate.aliases.isEmpty) true
      else {
        existingTemplate.aliases.forall { alias =>
          val allowed = isAliasAllowed(alias)
          if (!allowed) logger.debug(
            s"""[${blockContext.requestContext.id.show}] STOP: cannot allow to modify existing Component Template
               | [${existingTemplate.name.show}], because its alias [${alias.show}] is not allowed by rule
               | (it means that user has no access to it)""".oneLiner
          )
          allowed
        }
      }
    allAliasesAllowed
  }

  private def findTemplateBy(name: TemplateName, in: TemplateRequestBlockContext) = {
    in.requestContext.componentTemplates.find(_.name == name)
  }

  private def findTemplatesBy(namePattern: TemplateNamePattern, in: TemplateRequestBlockContext): Set[Template.ComponentTemplate] = {
    findTemplatesBy(Set(namePattern), in)
  }

  private def findTemplatesBy(namePatterns: Iterable[TemplateNamePattern], in: TemplateRequestBlockContext): Set[Template.ComponentTemplate] = {
    filterTemplates(namePatterns, in.requestContext.componentTemplates)
  }

  private def filterTemplatesNotAllowedAliases(templates: Set[Template])
                                              (implicit allowedIndices: AllowedIndices): Set[Template] = {
    templates.flatMap {
      case Template.ComponentTemplate(name, aliases) =>
        Set[Template](Template.ComponentTemplate(name, aliases.filter(isAliasAllowed)))
      case other =>
        Set[Template](other)
    }
  }
}

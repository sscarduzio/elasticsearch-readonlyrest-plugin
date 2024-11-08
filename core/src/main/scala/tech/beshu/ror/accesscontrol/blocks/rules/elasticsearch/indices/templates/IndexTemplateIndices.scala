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
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext.TemplatesTransformation
import tech.beshu.ror.accesscontrol.blocks.BlockContext.{RequestedIndex, TemplateRequestBlockContext}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.Rejected.Cause
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult.resultBasedOnCondition
import tech.beshu.ror.accesscontrol.domain.*
import tech.beshu.ror.accesscontrol.domain.TemplateOperation.{AddingIndexTemplateAndGetAllowedOnes, GettingIndexTemplates}
import tech.beshu.ror.implicits.*
import tech.beshu.ror.syntax.*
import tech.beshu.ror.utils.ScalaOps.*
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

private[indices] trait IndexTemplateIndices
  extends Logging {
  this: AllTemplateIndices =>

  protected def gettingIndexTemplates(templateNamePatterns: NonEmptyList[TemplateNamePattern])
                                     (implicit blockContext: TemplateRequestBlockContext,
                                      allowedIndices: AllowedIndices): RuleResult[TemplateRequestBlockContext] = {
    processGettingIndexTemplates(templateNamePatterns) match {
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

  protected def processGettingIndexTemplates(templateNamePatterns: NonEmptyList[TemplateNamePattern])
                                            (implicit blockContext: TemplateRequestBlockContext,
                                             allowedIndices: AllowedIndices): Either[Cause, (TemplateOperation.GettingIndexTemplates, TemplatesTransformation)] = {
    val (names, transformation) = getAllowedExistingIndexTemplates(templateNamePatterns)
    NonEmptyList
      .fromList(names)
      .map(names => (GettingIndexTemplates(names), transformation))
      .toRight(Cause.TemplateNotFound: Cause)
  }

  private def getAllowedExistingIndexTemplates(templateNamePatterns: NonEmptyList[TemplateNamePattern])
                                              (implicit blockContext: TemplateRequestBlockContext,
                                               allowedIndices: AllowedIndices): (List[TemplateNamePattern], TemplatesTransformation) = {
    logger.debug(
      s"""[${blockContext.requestContext.id.show}] * getting Index Templates for name patterns [${templateNamePatterns.show}] ...""".oneLiner
    )
    val existingTemplates = findTemplatesBy(templateNamePatterns.toList, in = blockContext)
    if (existingTemplates.isEmpty) {
      logger.debug(
        s"""[${blockContext.requestContext.id.show}] * no Index Templates for name patterns [${templateNamePatterns.show}] found ..."""
      )
      (templateNamePatterns.toList, ignoreAnyTemplate)
    } else {
      val filteredExistingTemplates = existingTemplates.filter(canViewExistingTemplate).toList
      NonEmptyList.fromList(filteredExistingTemplates) match {
        case Some(nonEmptyFilterTemplates) =>
          val namePatterns = nonEmptyFilterTemplates.map(t => TemplateNamePattern.from(t.name))
          (namePatterns.toList, filterTemplatesNotAllowedPatternsAndAliases(_))
        case None =>
          (Nil, ignoreAnyTemplate)
      }
    }
  }

  protected def addingIndexTemplate(newTemplateName: TemplateName,
                                    newTemplateIndicesPatterns: UniqueNonEmptyList[IndexPattern],
                                    aliases: Set[RequestedIndex[ClusterIndexName]])
                                   (implicit blockContext: TemplateRequestBlockContext,
                                    allowedIndices: AllowedIndices): RuleResult[TemplateRequestBlockContext] = {
    resultBasedOnCondition(blockContext) {
      processAddingIndexTemplate(newTemplateName, newTemplateIndicesPatterns, aliases)
    }
  }

  protected def addingIndexTemplateAndGetAllowedOnes(newTemplateName: TemplateName,
                                                     newTemplateIndicesPatterns: UniqueNonEmptyList[IndexPattern],
                                                     aliases: Set[RequestedIndex[ClusterIndexName]],
                                                     requestedTemplateNames: List[TemplateNamePattern])
                                                    (implicit blockContext: TemplateRequestBlockContext,
                                                     allowedIndices: AllowedIndices): RuleResult[TemplateRequestBlockContext] = {
    processAddingIndexTemplate(newTemplateName, newTemplateIndicesPatterns, aliases) match {
      case true =>
        val (filteredAllowedTemplates, transformation) = NonEmptyList.fromList(requestedTemplateNames) match {
          case Some(nonEmptyAllowedTemplateNames) => getAllowedExistingIndexTemplates(nonEmptyAllowedTemplateNames)
          case None => (List.empty, ignoreAnyTemplate)
        }
        RuleResult.fulfilled {
          blockContext
            .withTemplateOperation(
              AddingIndexTemplateAndGetAllowedOnes(newTemplateName, newTemplateIndicesPatterns, aliases, filteredAllowedTemplates)
            )
            .withResponseTemplateTransformation(transformation)
        }
      case false =>
        RuleResult.rejected()
    }
  }

  private def processAddingIndexTemplate(newTemplateName: TemplateName,
                                         newTemplateIndicesPatterns: UniqueNonEmptyList[IndexPattern],
                                         aliases: Set[RequestedIndex[ClusterIndexName]])
                                        (implicit blockContext: TemplateRequestBlockContext,
                                         allowedIndices: AllowedIndices): Boolean = {
    logger.debug(
      s"""[${blockContext.requestContext.id.show}] * adding Index Template [${newTemplateName.show}] with index
         | patterns [${newTemplateIndicesPatterns.show}] and aliases [${aliases.show}] ...""".oneLiner
    )
    findTemplateBy(name = newTemplateName, in = blockContext) match {
      case Some(existingTemplate) =>
        logger.debug(
          s"""[${blockContext.requestContext.id.show}] * Index Template with name [${existingTemplate.name.show}]
             | (indices patterns [${existingTemplate.patterns.show}]) exits ...""".oneLiner
        )
        canModifyExistingIndexTemplate(existingTemplate) &&
          canAddNewIndexTemplate(newTemplateName, newTemplateIndicesPatterns, aliases)
      case None =>
        canAddNewIndexTemplate(newTemplateName, newTemplateIndicesPatterns, aliases)
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
                                     newTemplateIndicesPatterns: UniqueNonEmptyList[IndexPattern],
                                     newTemplateAliases: Set[RequestedIndex[ClusterIndexName]])
                                    (implicit blockContext: TemplateRequestBlockContext,
                                     allowedIndices: AllowedIndices) = {
    logger.debug(
      s"""[${blockContext.requestContext.id.show}] * checking if Index Template [${newTemplateName.show}] with indices
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
          val allowed = isAliasAllowed(alias.name)
          if (!allowed) logger.debug(
            s"""[${blockContext.requestContext.id.show}] STOP: one of Template's [${newTemplateName.show}]
               | alias [${alias.show}] is forbidden.""".oneLiner
          )
          allowed
        }
      }
    allPatternAllowed && allAliasesAllowed
  }

  private def canViewExistingTemplate(existingTemplate: Template.IndexTemplate)
                                     (implicit blockContext: TemplateRequestBlockContext,
                                      allowedIndices: AllowedIndices) = {
    val isTemplateAllowed = existingTemplate.patterns.toList
      .exists { pattern => pattern.isAllowedByAny(allowedIndices.resolved) }
    if (!isTemplateAllowed) logger.debug(
      s"""[${blockContext.requestContext.id.show}] WARN: Index Template [${existingTemplate.name.show}] is forbidden
         | because none of its index patterns [${existingTemplate.patterns.show}] is allowed by the rule""".oneLiner
    )
    isTemplateAllowed
  }

  private def canModifyExistingIndexTemplate(existingTemplate: Template.IndexTemplate)
                                            (implicit blockContext: TemplateRequestBlockContext,
                                             allowedIndices: AllowedIndices) = {
    logger.debug(
      s"[${blockContext.requestContext.id.show}] * checking if Index Template [${existingTemplate.name.show}] can be modified by the user ..."
    )
    lazy val allPatternAllowed =
      existingTemplate.patterns.toList
        .forall { pattern =>
          val isPatternAllowed = allowedIndices.resolved.exists(pattern.isSubsetOf)
          if (!isPatternAllowed) logger.debug(
            s"""[${blockContext.requestContext.id.show}] STOP: cannot allow to modify existing Index Template
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
            s"""[${blockContext.requestContext.id.show}] STOP: cannot allow to modify existing Index Template
               | [${existingTemplate.name.show}], because its alias [${alias.show}] is not allowed by rule
               | (it means that user has no access to it)""".oneLiner
          )
          allowed
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

  private def findTemplatesBy(namePatterns: Iterable[TemplateNamePattern], in: TemplateRequestBlockContext): Set[Template.IndexTemplate] = {
    filterTemplates(namePatterns, in.requestContext.indexTemplates)
  }

  private def filterTemplatesNotAllowedPatternsAndAliases(templates: Set[Template])
                                                         (implicit allowedIndices: AllowedIndices): Set[Template] = {
    templates.flatMap {
      case Template.IndexTemplate(name, patterns, aliases) =>
        val onlyAllowedPatterns = patterns.filter(p => p.isAllowedByAny(allowedIndices.resolved))
        val onlyAllowedAliases = aliases.filter(isAliasAllowed)
        UniqueNonEmptyList.from(onlyAllowedPatterns) match {
          case Some(nonEmptyAllowedPatterns) =>
            Set[Template](Template.IndexTemplate(name, nonEmptyAllowedPatterns, onlyAllowedAliases))
          case None =>
            Set.empty[Template]
        }
      case other =>
        Set[Template](other)
    }
  }

  private lazy val ignoreAnyTemplate: TemplatesTransformation = _ => Set.empty
}

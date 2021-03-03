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

import cats.Show
import cats.data.NonEmptySet
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.rules.utils.{MatcherWithWildcardsScalaAdapter, UniqueIdentifierGenerator}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.domain.TemplateOperation._
import tech.beshu.ror.accesscontrol.domain._
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll
import tech.beshu.ror.implicits._
import tech.beshu.ror.utils.ScalaOps._

private[indicesrule] trait AllTemplateIndices
  extends IndexTemplateIndices
    with ComponentTemplateIndices
    with LegacyTemplatesIndices
    with Logging {

  protected def settings: IndicesRule.Settings

  protected def identifierGenerator: UniqueIdentifierGenerator

  protected def processTemplateRequest(blockContext: TemplateRequestBlockContext): RuleResult[TemplateRequestBlockContext] = {
    implicit val allowedIndices: AllowedIndices = new AllowedIndices(settings.allowedIndices, blockContext)
    logger.debug(
      s"""[${blockContext.requestContext.id.show}] Checking - indices and aliases in Template related request.
         | Allowed indices by the rule: [${allowedIndices.show}]:""".oneLiner
    )
    implicit val _ = blockContext
    val result = blockContext.templateOperation match {
      case GettingLegacyAndIndexTemplates(gettingLegacyTemplates, gettingIndexTemplates) =>
        gettingLegacyAndIndexTemplates(gettingLegacyTemplates, gettingIndexTemplates)
      case GettingLegacyTemplates(namePatterns) => gettingLegacyTemplates(namePatterns)
      case AddingLegacyTemplate(name, patterns, aliases) => addingLegacyTemplate(name, patterns, aliases)
      case DeletingLegacyTemplates(namePatterns) => deletingLegacyTemplates(namePatterns)
      case GettingIndexTemplates(namePatterns) => gettingIndexTemplates(namePatterns)
      case AddingIndexTemplate(name, patterns, aliases) => addingIndexTemplate(name, patterns, aliases)
      case AddingIndexTemplateAndGetAllowedOnes(name, patterns, aliases, templateNamePatterns) =>
        addingIndexTemplateAndGetAllowedOnes(name, patterns, aliases, templateNamePatterns)
      case DeletingIndexTemplates(namePatterns) => deletingIndexTemplates(namePatterns)
      case GettingComponentTemplates(namePatterns) => gettingComponentTemplates(namePatterns)
      case AddingComponentTemplate(name, aliases) => addingComponentTemplate(name, aliases)
      case DeletingComponentTemplates(namePatterns) => deletingComponentTemplates(namePatterns)
    }
    result match {
      case RuleResult.Fulfilled(b) => RuleResult.fulfilled(b.withAllAllowedIndices(allowedIndices.resolved))
      case rejected@RuleResult.Rejected(_) => rejected
    }
  }

  private def gettingLegacyAndIndexTemplates(gettingLegacyTemplates: GettingLegacyTemplates, gettingIndexTemplates: GettingIndexTemplates)
                                            (implicit blockContext: TemplateRequestBlockContext,
                                             allowedIndices: AllowedIndices): RuleResult[TemplateRequestBlockContext] = {
    val gettingLegacyTemplatesResult = processGettingLegacyTemplates(gettingLegacyTemplates.namePatterns)
    val gettingIndexTemplatesResult = processGettingIndexTemplates(gettingIndexTemplates.namePatterns)

    (gettingLegacyTemplatesResult, gettingIndexTemplatesResult) match {
      case (Right((o1, t1)), Right((o2, t2))) =>
        val finalOperation = GettingLegacyAndIndexTemplates(o1, o2)
        RuleResult.fulfilled {
          blockContext
            .withTemplateOperation(finalOperation)
            .withResponseTemplateTransformation(t1 andThen t2)
        }
      case (Right((o1, t1)), _) =>
        RuleResult.fulfilled {
          blockContext
            .withTemplateOperation(o1)
            .withResponseTemplateTransformation(t1)
        }
      case (_, Right((o2, t2))) =>
        RuleResult.fulfilled {
          blockContext
            .withTemplateOperation(o2)
            .withResponseTemplateTransformation(t2)
        }
      case (Left(cause), Left(_)) =>
        RuleResult.rejected(Some(cause))
    }
  }

  private[indicesrule] def isAliasAllowed(alias: IndexName)
                                         (implicit allowedIndices: AllowedIndices) = {
    alias.isAllowedBy(allowedIndices.resolved)
  }

  private[indicesrule] def filterTemplates[T <: Template](allowedNamePatterns: Set[TemplateNamePattern],
                                                          requestedTemplates: Set[T]): Set[T] = {
    val matcher = MatcherWithWildcardsScalaAdapter.create(allowedNamePatterns)
    val templateByName: Map[TemplateNamePattern, Set[T]] = requestedTemplates.groupBy(t => TemplateNamePattern(t.name.value))
    val filteredTemplateNames = matcher.filter(templateByName.keys.toSet)
    templateByName.filterKeys(filteredTemplateNames.contains).values.toSet.flatten
  }

  private[indicesrule] class AllowedIndices(allowedIndices: NonEmptySet[RuntimeMultiResolvableVariable[IndexName]],
                                            val blockContext: TemplateRequestBlockContext) {
    val resolved: Set[IndexName] = resolveAll(settings.allowedIndices.toNonEmptyList, blockContext).toSet
  }
  private[indicesrule] object AllowedIndices {
    implicit def show: Show[AllowedIndices] = Show.show(_.resolved.map(_.show).mkStringOrEmptyString("", ",", ""))
  }

  private[indicesrule] sealed trait PartialResult[T]
  private[indicesrule] object Result {
    sealed case class Allowed[T](value: T) extends PartialResult[T]
    sealed case class NotFound[T](value: T) extends PartialResult[T]
    sealed case class Forbidden[T](value: T) extends PartialResult[T]
  }
}

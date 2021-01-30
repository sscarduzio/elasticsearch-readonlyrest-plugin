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

import eu.timepit.refined.auto._
import cats.Show
import cats.data.NonEmptySet
import eu.timepit.refined.types.string.NonEmptyString
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.BlockContext.TemplateRequestBlockContext
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.RuleResult
import tech.beshu.ror.accesscontrol.blocks.rules.utils.UniqueIdentifierGenerator
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
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
    blockContext.templateOperation match {
      case TemplateOperation.GettingLegacyTemplates(namePatterns) => gettingLegacyTemplates(namePatterns)
      case TemplateOperation.AddingLegacyTemplate(name, patterns) => addingLegacyTemplate(name, patterns)
      case TemplateOperation.DeletingLegacyTemplates(namePatterns) => deletingLegacyTemplates(namePatterns)
      case TemplateOperation.GettingIndexTemplates(namePatterns) => gettingIndexTemplates(namePatterns)
      case TemplateOperation.AddingIndexTemplate(name, patterns, aliases) => addingIndexTemplate(name, patterns, aliases)
      case TemplateOperation.DeletingIndexTemplates(namePatterns) => deletingIndexTemplates(namePatterns)
      case TemplateOperation.GettingComponentTemplates(namePatterns) => gettingComponentTemplates(namePatterns)
      case TemplateOperation.AddingComponentTemplate(name, aliases) => addingComponentTemplate(name, aliases)
      case TemplateOperation.DeletingComponentTemplates(namePatterns) => deletingComponentTemplates(namePatterns)
    }
  }

  private [indicesrule] def generateRorArtificialName(templateNamePattern: TemplateNamePattern): TemplateNamePattern = {
    val nonexistentTemplateNamePattern = s"${templateNamePattern.value}_ROR_${identifierGenerator.generate(10)}"
    TemplateNamePattern(NonEmptyString.unsafeFrom(nonexistentTemplateNamePattern))
  }

  private [indicesrule]  def isAliasAllowed(alias: IndexName)
                                           (implicit allowedIndices: AllowedIndices) = {
    alias match {
      case Placeholder(placeholder) =>
        val potentialAliases = allowedIndices.resolved.map(i => placeholder.index(i.value))
        potentialAliases.exists { alias => allowedIndices.resolved.exists(_.matches(alias)) }
      case _ =>
        allowedIndices.resolved.exists(_.matches(alias))
    }
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

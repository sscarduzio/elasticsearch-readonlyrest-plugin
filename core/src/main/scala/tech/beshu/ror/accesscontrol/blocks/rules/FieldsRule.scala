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

import cats.data.NonEmptyList
import cats.implicits._
import monix.eval.Task
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.{AliasRequestBlockContextUpdater, CurrentUserMetadataRequestBlockContextUpdater, FilterableMultiRequestBlockContextUpdater, FilterableRequestBlockContextUpdater, GeneralIndexRequestBlockContextUpdater, GeneralNonIndexRequestBlockContextUpdater, MultiIndexRequestBlockContextUpdater, RepositoryRequestBlockContextUpdater, SnapshotRequestBlockContextUpdater, TemplateRequestBlockContextUpdater}
import tech.beshu.ror.accesscontrol.blocks.rules.FieldsRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, BlockContextWithFieldsUpdater}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions.{AccessMode, DocumentField}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsUsage.UsedField.{FieldWithWildcard, SpecificField}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsUsage.{CantExtractFields, NotUsingFields, UsingFields}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.Strategy.{BasedOnBlockContextOnly, LuceneContextHeaderApproach}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.{FieldsRestrictions, FieldsUsage, Strategy}
import tech.beshu.ror.accesscontrol.domain.Header.Name
import tech.beshu.ror.accesscontrol.domain.{FieldLevelSecurity, Header}
import tech.beshu.ror.accesscontrol.headerValues.transientFieldsToHeaderValue
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll
import tech.beshu.ror.fls.FieldsPolicy
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class FieldsRule(val settings: Settings)
  extends RegularRule {

  override val name: Rule.Name = FieldsRule.name

  override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    if (!blockContext.requestContext.isReadOnlyRequest)
      RuleResult.Rejected()
    else {
      val maybeResolvedFields = resolveAll(settings.fields.toNonEmptyList, blockContext)
      UniqueNonEmptyList.fromList(maybeResolvedFields) match {
        case Some(resolvedFields) =>
          val fieldsRestrictions = FieldsRestrictions(resolvedFields, settings.accessMode)
          val strategy = resolveFLSStrategy(blockContext.requestContext.fieldsUsage, fieldsRestrictions)
          val fieldLevelSecurity = FieldLevelSecurity(fieldsRestrictions, strategy)
          val updatedBlockContext = updateBlockContext(blockContext, fieldLevelSecurity)

          RuleResult.Fulfilled(updatedBlockContext)
        case _ =>
          RuleResult.Rejected()
      }
    }
  }

  private def resolveFLSStrategy(fieldsUsage: FieldsUsage,
                                 fieldsRestrictions: FieldsRestrictions): Strategy = fieldsUsage match {
    case CantExtractFields =>
      LuceneContextHeaderApproach
    case NotUsingFields =>
      BasedOnBlockContextOnly.NothingNotAllowedToModify
    case UsingFields(usedFields) =>
      verifyUsedFields(fieldsRestrictions, usedFields)
  }

  private def verifyUsedFields(fieldsRestrictions: FieldsRestrictions,
                               usedFields: NonEmptyList[FieldsUsage.UsedField]) = {
    val (specificFields, fieldsWithWildcard) = extractSpecificAndWildcardFields(usedFields)
    if (fieldsWithWildcard.nonEmpty) {
      LuceneContextHeaderApproach
    } else {
      extractSpecificNotAllowedFields(specificFields, fieldsRestrictions) match {
        case Some(notAllowedFields) =>
          BasedOnBlockContextOnly.NotAllowedFieldsToModify(notAllowedFields)
        case None =>
          BasedOnBlockContextOnly.NothingNotAllowedToModify
      }
    }
  }

  private def extractSpecificAndWildcardFields(usedFields: NonEmptyList[FieldsUsage.UsedField]) = {
    usedFields.toList.partitionEither {
      case specific: SpecificField => Left(specific)
      case withWildcard: FieldWithWildcard => Right(withWildcard)
    }
  }

  private def extractSpecificNotAllowedFields(usedFields: List[SpecificField],
                                              fieldsRestrictions: FieldsRestrictions) = {
    val fieldsPolicy = new FieldsPolicy(fieldsRestrictions)
    usedFields
      .filterNot(field => fieldsPolicy.canKeep(field.value))
      .toNel
  }

  private def updateBlockContext[B <: BlockContext : BlockContextUpdater](blockContext: B,
                                                                          fieldLevelSecurity: FieldLevelSecurity): B = {
    BlockContextUpdater[B] match {
      case CurrentUserMetadataRequestBlockContextUpdater => blockContext
      case GeneralNonIndexRequestBlockContextUpdater => blockContext
      case RepositoryRequestBlockContextUpdater => blockContext
      case SnapshotRequestBlockContextUpdater => blockContext
      case TemplateRequestBlockContextUpdater => blockContext
      case GeneralIndexRequestBlockContextUpdater => blockContext
      case MultiIndexRequestBlockContextUpdater => blockContext
      case AliasRequestBlockContextUpdater => blockContext
      case FilterableRequestBlockContextUpdater => updateFilterableBlockContext(blockContext, fieldLevelSecurity)
      case FilterableMultiRequestBlockContextUpdater => updateFilterableBlockContext(blockContext, fieldLevelSecurity)
    }
  }

  private def updateFilterableBlockContext[B <: BlockContext : BlockContextUpdater : BlockContextWithFieldsUpdater](blockContext: B,
                                                                                                                    fieldLevelSecurity: FieldLevelSecurity) = {

    fieldLevelSecurity.strategy match {
      case LuceneContextHeaderApproach =>
        blockContext
          .withAddedContextHeader(createContextHeader(fieldLevelSecurity.restrictions))
          .withFields(fieldLevelSecurity)
      case _: BasedOnBlockContextOnly =>
        blockContext.withFields(fieldLevelSecurity)
    }
  }

  private def createContextHeader(fieldsRestrictions: FieldsRestrictions) = {
    new Header(
      Name.transientFields,
      transientFieldsToHeaderValue.toRawValue(fieldsRestrictions)
    )
  }
}

object FieldsRule {
  val name = Rule.Name("fields")

  final case class Settings(fields: UniqueNonEmptyList[RuntimeMultiResolvableVariable[DocumentField]],
                            accessMode: AccessMode)

}

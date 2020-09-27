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
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, BlockContextWithFLSUpdater}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions.{AccessMode, DocumentField}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage.UsedField.{FieldWithWildcard, SpecificField}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage.{CantExtractFields, NotUsingFields, UsingFields}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.Strategy.{BasedOnBlockContextOnly, LuceneContextHeaderApproach}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.{FieldsRestrictions, RequestFieldsUsage, Strategy}
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
      handleReadOnlyRequest(blockContext)
    }
  }

  private def handleReadOnlyRequest[B <: BlockContext : BlockContextUpdater](blockContext: B): RuleResult[B] = {
    BlockContextUpdater[B] match {
      case CurrentUserMetadataRequestBlockContextUpdater => RuleResult.Fulfilled(blockContext)
      case GeneralNonIndexRequestBlockContextUpdater => RuleResult.Fulfilled(blockContext)
      case RepositoryRequestBlockContextUpdater => RuleResult.Fulfilled(blockContext)
      case SnapshotRequestBlockContextUpdater => RuleResult.Fulfilled(blockContext)
      case TemplateRequestBlockContextUpdater => RuleResult.Fulfilled(blockContext)
      case GeneralIndexRequestBlockContextUpdater => RuleResult.Fulfilled(blockContext)
      case MultiIndexRequestBlockContextUpdater => RuleResult.Fulfilled(blockContext)
      case AliasRequestBlockContextUpdater => RuleResult.Fulfilled(blockContext)
      case FilterableRequestBlockContextUpdater => processFilterableBlockContext(blockContext)
      case FilterableMultiRequestBlockContextUpdater => processFilterableBlockContext(blockContext)
    }
  }

  private def processFilterableBlockContext[B <: BlockContext : BlockContextUpdater : BlockContextWithFLSUpdater](blockContext: B): RuleResult[B] = {
    val maybeResolvedFields = resolveAll(settings.fields.toNonEmptyList, blockContext)
    UniqueNonEmptyList.fromList(maybeResolvedFields) match {
      case Some(resolvedFields) =>
        val fieldsRestrictions = FieldsRestrictions(resolvedFields, settings.accessMode)
        val strategy = resolveFLSStrategy(blockContext.requestContext.requestFieldsUsage, fieldsRestrictions)
        val fieldLevelSecurity = FieldLevelSecurity(fieldsRestrictions, strategy)
        val updatedBlockContext = updateFilterableBlockContext(blockContext, fieldLevelSecurity)

        RuleResult.Fulfilled(updatedBlockContext)
      case None =>
        RuleResult.Rejected()
    }
  }


  private def resolveFLSStrategy(fieldsUsage: RequestFieldsUsage,
                                 fieldsRestrictions: FieldsRestrictions): Strategy = fieldsUsage match {
    case CantExtractFields =>
      LuceneContextHeaderApproach
    case NotUsingFields =>
      BasedOnBlockContextOnly.NothingNotAllowedUsed
    case UsingFields(usedFields) =>
      resolveStrategyBasedOnUsedFields(fieldsRestrictions, usedFields)
  }

  private def resolveStrategyBasedOnUsedFields(fieldsRestrictions: FieldsRestrictions,
                                               usedFields: NonEmptyList[RequestFieldsUsage.UsedField]) = {
    val (specificFields, fieldsWithWildcard) = extractSpecificAndWildcardFields(usedFields)
    if (fieldsWithWildcard.nonEmpty) {
      LuceneContextHeaderApproach
    } else {
      extractSpecificNotAllowedFields(specificFields, fieldsRestrictions) match {
        case Some(notAllowedFields) =>
          BasedOnBlockContextOnly.NotAllowedFieldsUsed(notAllowedFields)
        case None =>
          BasedOnBlockContextOnly.NothingNotAllowedUsed
      }
    }
  }

  private def extractSpecificAndWildcardFields(usedFields: NonEmptyList[RequestFieldsUsage.UsedField]) = {
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

  private def updateFilterableBlockContext[B <: BlockContext : BlockContextUpdater : BlockContextWithFLSUpdater](blockContext: B,
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
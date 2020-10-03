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
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater.{AliasRequestBlockContextUpdater, CurrentUserMetadataRequestBlockContextUpdater, FilterableMultiRequestBlockContextUpdater, FilterableRequestBlockContextUpdater, GeneralIndexRequestBlockContextUpdater, GeneralNonIndexRequestBlockContextUpdater, MultiIndexRequestBlockContextUpdater, RepositoryRequestBlockContextUpdater, SnapshotRequestBlockContextUpdater, TemplateRequestBlockContextUpdater}
import tech.beshu.ror.accesscontrol.blocks.rules.FieldsRule.{FLSMode, Settings}
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, BlockContextWithFLSUpdater}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions.{AccessMode, DocumentField}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage.UsedField.{FieldWithWildcard, SpecificField}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage.{CannotExtractFields, NotUsingFields, UsingFields}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.Strategy.{BasedOnBlockContextOnly, FlsAtLuceneLevelApproach}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.{FieldsRestrictions, RequestFieldsUsage, Strategy}
import tech.beshu.ror.accesscontrol.domain.Header.Name
import tech.beshu.ror.accesscontrol.domain.{FieldLevelSecurity, Header}
import tech.beshu.ror.accesscontrol.headerValues.transientFieldsToHeaderValue
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll
import tech.beshu.ror.fls.FieldsPolicy
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class FieldsRule(val settings: Settings)
  extends RegularRule
    with Logging {

  override val name: Rule.Name = FieldsRule.name

  override def check[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    if (blockContext.requestContext.isReadOnlyRequest) {
      handleReadOnlyRequest(blockContext)
    } else {
      RuleResult.Rejected()
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
        processBlockContextUsingDefinedFLSMode(blockContext, resolvedFields)
      case None =>
        logger.warn(s"[${blockContext.requestContext.id.show}] Could not resolve any variable for field rule.")
        RuleResult.Rejected()
    }
  }

  private def processBlockContextUsingDefinedFLSMode[B <: BlockContext : BlockContextUpdater : BlockContextWithFLSUpdater](blockContext: B,
                                                                                                                           resolvedFields: UniqueNonEmptyList[DocumentField]): RuleResult[B] = {
    val fieldsRestrictions = FieldsRestrictions(resolvedFields, settings.accessMode)

    settings.flsMode match {
      case FLSMode.Legacy =>
        fulfillRuleWithResolvedStrategy(blockContext, fieldsRestrictions, resolvedStrategy = FlsAtLuceneLevelApproach)
      case FLSMode.Hybrid =>
        val resolvedStrategy = resolveFLSStrategyBasedOnFieldsUsage(blockContext.requestContext.requestFieldsUsage, fieldsRestrictions)
        fulfillRuleWithResolvedStrategy(blockContext, fieldsRestrictions, resolvedStrategy)
      case FLSMode.Proxy =>
        processProxyMode(blockContext, fieldsRestrictions)
    }
  }

  private def processProxyMode[B <: BlockContext : BlockContextUpdater : BlockContextWithFLSUpdater](blockContext: B,
                                                                                                     fieldsRestrictions: FieldsRestrictions): RuleResult[B] = {
    resolveFLSStrategyBasedOnFieldsUsage(blockContext.requestContext.requestFieldsUsage, fieldsRestrictions) match {
      case basedOnBlockContext: BasedOnBlockContextOnly =>
        fulfillRuleWithResolvedStrategy(blockContext, fieldsRestrictions, resolvedStrategy = basedOnBlockContext)
      case Strategy.FlsAtLuceneLevelApproach =>
        logger.warn(s"[${blockContext.requestContext.id.show}] Could not use fls at lucene level in proxy mode.")
        RuleResult.Rejected()
    }
  }

  private def fulfillRuleWithResolvedStrategy[B <: BlockContext : BlockContextUpdater : BlockContextWithFLSUpdater](blockContext: B,
                                                                                                                    fieldsRestrictions: FieldsRestrictions,
                                                                                                                    resolvedStrategy: Strategy): RuleResult[B] = {
    val fieldLevelSecurity = FieldLevelSecurity(fieldsRestrictions, resolvedStrategy)
    val updatedBlockContext = updateFilterableBlockContext(blockContext, fieldLevelSecurity)
    RuleResult.Fulfilled(updatedBlockContext)
  }

  private def resolveFLSStrategyBasedOnFieldsUsage(fieldsUsage: RequestFieldsUsage,
                                                   fieldsRestrictions: FieldsRestrictions): Strategy = {
    fieldsUsage match {
      case CannotExtractFields =>
        FlsAtLuceneLevelApproach
      case NotUsingFields =>
        BasedOnBlockContextOnly.EverythingAllowed
      case UsingFields(usedFields) =>
        resolveStrategyBasedOnUsedFields(fieldsRestrictions, usedFields)
    }
  }

  private def resolveStrategyBasedOnUsedFields(fieldsRestrictions: FieldsRestrictions,
                                               usedFields: NonEmptyList[RequestFieldsUsage.UsedField]) = {
    val (specificFields, fieldsWithWildcard) = extractSpecificAndWildcardFields(usedFields)
    if (fieldsWithWildcard.nonEmpty) {
      FlsAtLuceneLevelApproach
    } else {
      extractSpecificNotAllowedFields(specificFields, fieldsRestrictions) match {
        case Some(notAllowedFields) =>
          BasedOnBlockContextOnly.NotAllowedFieldsUsed(notAllowedFields)
        case None =>
          BasedOnBlockContextOnly.EverythingAllowed
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
      case FlsAtLuceneLevelApproach =>
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

  sealed trait FLSMode
  object FLSMode {
    case object Legacy extends FLSMode
    case object Hybrid extends FLSMode
    case object Proxy extends FLSMode

    val default = Hybrid
  }

  final case class Settings(fields: UniqueNonEmptyList[RuntimeMultiResolvableVariable[DocumentField]],
                            accessMode: AccessMode,
                            flsMode: FLSMode)
}
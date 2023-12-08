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
package tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch

import cats.data.NonEmptyList
import cats.implicits._
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.BlockContext.AllowsFieldsInRequest
import tech.beshu.ror.accesscontrol.blocks.BlockContext.AllowsFieldsInRequest._
import tech.beshu.ror.accesscontrol.blocks.BlockContextUpdater._
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.FieldsRule.Settings
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{RegularRule, RuleName, RuleResult}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.blocks.{BlockContext, BlockContextUpdater, BlockContextWithFLSUpdater}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.FieldsRestrictions.{AccessMode, DocumentField}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage.UsedField.{FieldWithWildcard, SpecificField}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.RequestFieldsUsage.{CannotExtractFields, NotUsingFields, UsingFields}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.Strategy.{BasedOnBlockContextOnly, FlsAtLuceneLevelApproach}
import tech.beshu.ror.accesscontrol.domain.FieldLevelSecurity.{FieldsRestrictions, RequestFieldsUsage, Strategy}
import tech.beshu.ror.accesscontrol.factory.GlobalSettings.FlsEngine
import tech.beshu.ror.accesscontrol.utils.RuntimeMultiResolvableVariableOps.resolveAll
import tech.beshu.ror.fls.FieldsPolicy
import tech.beshu.ror.utils.uniquelist.UniqueNonEmptyList

class FieldsRule(val settings: Settings)
  extends RegularRule
    with Logging {

  override val name: Rule.Name = FieldsRule.Name.name

  override def regularCheck[B <: BlockContext : BlockContextUpdater](blockContext: B): Task[RuleResult[B]] = Task {
    blockContext.requestContext match {
      case r if r.isReadOnlyRequest && !r.action.isRorAction => handleReadOnlyRequest(blockContext)
      case _ => RuleResult.Rejected()
    }
  }

  private def handleReadOnlyRequest[B <: BlockContext : BlockContextUpdater](blockContext: B): RuleResult[B] = {
    BlockContextUpdater[B] match {
      case CurrentUserMetadataRequestBlockContextUpdater => RuleResult.Fulfilled(blockContext)
      case GeneralNonIndexRequestBlockContextUpdater => RuleResult.Fulfilled(blockContext)
      case RepositoryRequestBlockContextUpdater => RuleResult.Fulfilled(blockContext)
      case SnapshotRequestBlockContextUpdater => RuleResult.Fulfilled(blockContext)
      case DataStreamRequestBlockContextUpdater => RuleResult.Fulfilled(blockContext)
      case TemplateRequestBlockContextUpdater => RuleResult.Fulfilled(blockContext)
      case GeneralIndexRequestBlockContextUpdater => RuleResult.Fulfilled(blockContext)
      case MultiIndexRequestBlockContextUpdater => RuleResult.Fulfilled(blockContext)
      case AliasRequestBlockContextUpdater => RuleResult.Fulfilled(blockContext)
      case FilterableRequestBlockContextUpdater => processFilterableBlockContext(blockContext)
      case FilterableMultiRequestBlockContextUpdater => processFilterableBlockContext(blockContext)
      case RorApiRequestBlockContextUpdater => RuleResult.Fulfilled(blockContext)
    }
  }

  private def processFilterableBlockContext[B <: BlockContext : BlockContextWithFLSUpdater : AllowsFieldsInRequest](blockContext: B): RuleResult[B] = {
    val maybeResolvedFields = resolveAll(settings.fields.toNonEmptyList, blockContext)
    UniqueNonEmptyList.fromIterable(maybeResolvedFields) match {
      case Some(resolvedFields) =>
        processBlockContextUsingDefinedFLSMode(blockContext, resolvedFields)
      case None =>
        logger.warn(s"[${blockContext.requestContext.id.show}] Could not resolve any variable for field rule.")
        RuleResult.Rejected()
    }
  }

  private def processBlockContextUsingDefinedFLSMode[B <: BlockContext : BlockContextWithFLSUpdater : AllowsFieldsInRequest](blockContext: B,
                                                                                                                             resolvedFields: UniqueNonEmptyList[DocumentField]): RuleResult[B] = {
    val fieldsRestrictions = FieldsRestrictions(resolvedFields, settings.accessMode)
    settings.flsEngine match {
      case FlsEngine.Lucene =>
        fulfillRuleWithResolvedStrategy(blockContext, fieldsRestrictions, resolvedStrategy = FlsAtLuceneLevelApproach)
      case FlsEngine.ESWithLucene =>
        val resolvedStrategy = resolveFLSStrategyBasedOnFieldsUsage(blockContext.requestFieldsUsage, fieldsRestrictions)
        fulfillRuleWithResolvedStrategy(blockContext, fieldsRestrictions, resolvedStrategy)
      case FlsEngine.ES =>
        processRuleWithEsEngine(blockContext, fieldsRestrictions)
    }
  }

  private def processRuleWithEsEngine[B <: BlockContext : BlockContextWithFLSUpdater : AllowsFieldsInRequest](blockContext: B,
                                                                                                              fieldsRestrictions: FieldsRestrictions): RuleResult[B] = {
    resolveFLSStrategyBasedOnFieldsUsage(blockContext.requestFieldsUsage, fieldsRestrictions) match {
      case basedOnBlockContext: BasedOnBlockContextOnly =>
        fulfillRuleWithResolvedStrategy(blockContext, fieldsRestrictions, resolvedStrategy = basedOnBlockContext)
      case Strategy.FlsAtLuceneLevelApproach =>
        logger.warn(s"[${blockContext.requestContext.id.show}] Could not use fls at lucene level with ES engine. Rejected.")
        RuleResult.Rejected()
    }
  }

  private def fulfillRuleWithResolvedStrategy[B <: BlockContext : BlockContextWithFLSUpdater](blockContext: B,
                                                                                              fieldsRestrictions: FieldsRestrictions,
                                                                                              resolvedStrategy: Strategy): RuleResult[B] = {
    val fieldLevelSecurity = FieldLevelSecurity(fieldsRestrictions, resolvedStrategy)
    val updatedBlockContext = blockContext.withFields(fieldLevelSecurity)
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
}

object FieldsRule {

  implicit case object Name extends RuleName[FieldsRule] {
    override val name = Rule.Name("fields")
  }

  final case class Settings(fields: UniqueNonEmptyList[RuntimeMultiResolvableVariable[DocumentField]],
                            accessMode: AccessMode,
                            flsEngine: FlsEngine)

}
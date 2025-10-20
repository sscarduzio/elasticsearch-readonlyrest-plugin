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
package tech.beshu.ror.accesscontrol.factory

import cats.data.*
import cats.data.Validated.*
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthenticationRule, AuthorizationRule}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseGroupsRule
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.{ActionsRule, FieldsRule, FilterRule, ResponseFieldsRule}
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.*
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.RequirementVerifier
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.UsageRequirement.ComplianceResult
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.VariableUsage.{NotUsingVariable, UsingVariable}
import tech.beshu.ror.accesscontrol.domain.KibanaAccess
import tech.beshu.ror.accesscontrol.factory.BlockValidator.BlockValidationError.{KibanaRuleTogetherWith, KibanaUserDataRuleTogetherWith, RuleDoesNotMeetRequirement}
import tech.beshu.ror.implicits.*

object BlockValidator extends Logging {

  def validate(rules: NonEmptyList[RuleDefinition[Rule]]): ValidatedNel[BlockValidationError, Unit] = {
    (
      validateAuthorizationWithAuthenticationPrinciple(rules),
      validateOnlyOneAuthenticationRulePrinciple(rules),
      validateRequirementsForRulesUsingVariables(rules),
      validateKibanaRuleInContextOfOtherRules(rules),
    ).mapN { case (_, _, _, _) => () }
  }

  private def validateAuthorizationWithAuthenticationPrinciple(rules: NonEmptyList[RuleDefinition[Rule]]): ValidatedNel[BlockValidationError, Unit] = {
    rules.find(_.rule.isInstanceOf[AuthorizationRule]) match {
      case None => Validated.Valid(())
      case Some(_) if rules.exists(_.rule.isInstanceOf[AuthenticationRule]) => Validated.Valid(())
      case Some(_) => Validated.Invalid(NonEmptyList.one(BlockValidationError.AuthorizationWithoutAuthentication))
    }
  }

  private def validateOnlyOneAuthenticationRulePrinciple(rules: NonEmptyList[RuleDefinition[Rule]]) = {
    rules
      .map(_.rule)
      .collect { case a: AuthenticationRule => a }
      .filter {
        case _: BaseGroupsRule[_] => false
        case _ => true
      } match {
      case Nil | _ :: Nil =>
        Validated.Valid(())
      case moreThanOne =>
        Validated.Invalid(NonEmptyList.one(
          BlockValidationError.OnlyOneAuthenticationRuleAllowed(NonEmptyList.fromListUnsafe(moreThanOne))
        ))
    }
  }

  private def validateRequirementsForRulesUsingVariables(allRules: NonEmptyList[RuleDefinition[Rule]]): ValidatedNel[BlockValidationError, Unit] = {
    allRules.toList
      .map(validateRequirementsForSingleRule(allRules.map(_.rule))) match {
      case Nil => Validated.Valid(())
      case head :: tail => NonEmptyList(head, tail).sequence_
    }
  }

  private def validateKibanaRuleInContextOfOtherRules(ruleDefs: NonEmptyList[RuleDefinition[Rule]]) = {
    val allRules = ruleDefs.map(_.rule)
    findKibanaRelatedRules(allRules) match {
      case Some(kibanaRules) =>
        (
          validateIfKibanaUserDataRuleIsNotUsedWithOldDeprecatedKibanaRules(kibanaRules, allRules),
          validateIfKibanaRelatedRulesCoexistenceWithOther(kibanaRules, allRules)
        ).mapN { case _ => () }
      case None =>
        Validated.Valid(())
    }
  }

  private def validateIfKibanaRelatedRulesCoexistenceWithOther(kibanaRulesInBlock: NonEmptyList[KibanaRelatedRule],
                                                               allRulesInBlock: NonEmptyList[Rule]) = {
    NonEmptyList.fromList {
      allRulesInBlock.toList.flatMap(validateRuleUsageInContextOf(kibanaRulesInBlock))
    } match {
      case Some(errors) => Validated.Invalid(errors)
      case None => Validated.Valid(())
    }
  }

  private def validateRuleUsageInContextOf(kibanaRules: NonEmptyList[KibanaRelatedRule]): Rule => Option[KibanaRuleTogetherWith] = {
    case _: ActionsRule =>
      determineConfiguredKibanaAccessIn(kibanaRules) match {
        case None | Some(KibanaAccess.Unrestricted) => None
        case Some(_) => Some(KibanaRuleTogetherWith.ActionsRule)
      }
    case _: FilterRule =>
      Some(KibanaRuleTogetherWith.FilterRule)
    case _: FieldsRule =>
      Some(KibanaRuleTogetherWith.FieldsRule)
    case _: ResponseFieldsRule =>
      Some(KibanaRuleTogetherWith.ResponseFieldsRule)
    case _ =>
      None
  }

  private def findKibanaRelatedRules(rules: NonEmptyList[Rule]): Option[NonEmptyList[KibanaRelatedRule]] = {
    NonEmptyList.fromList {
      rules.collect {
        case r: KibanaRelatedRule => r
      }
    }
  }

  private def determineConfiguredKibanaAccessIn(kibanaRules: NonEmptyList[KibanaRelatedRule]) = {
    kibanaRules.collect {
      case r: KibanaAccessRule => r.settings.access
      case r: KibanaUserDataRule => r.settings.access
    } match {
      case Nil => None
      case head :: rest =>
        logger.warn("???") // todo:
        Some(head)
    }
  }

  private def validateIfKibanaUserDataRuleIsNotUsedWithOldDeprecatedKibanaRules(kibanaRulesInBlock: NonEmptyList[KibanaRelatedRule],
                                                                                allRulesInBlock: NonEmptyList[Rule]) = {
    if (containsKibanaUserDataRule(kibanaRulesInBlock)) {
      NonEmptyList
        .fromList {
          allRulesInBlock
            .collect[BlockValidationError] {
              case _: KibanaAccessRule => KibanaUserDataRuleTogetherWith.KibanaAccessRule
              case _: KibanaHideAppsRule => KibanaUserDataRuleTogetherWith.KibanaHideAppsRule
              case _: KibanaIndexRule => KibanaUserDataRuleTogetherWith.KibanaIndexRule
              case _: KibanaTemplateIndexRule => KibanaUserDataRuleTogetherWith.KibanaTemplateIndexRule
            }
        } match {
        case None => Validated.Valid(())
        case Some(errors) => Validated.Invalid(errors)
      }
    } else {
      Validated.Valid(())
    }
  }

  private def containsKibanaUserDataRule(rules: NonEmptyList[KibanaRelatedRule]) = {
    rules.exists {
      case _: KibanaUserDataRule => true
      case _ => false
    }
  }

  private def validateRequirementsForSingleRule(allRules: NonEmptyList[Rule])
                                               (ruleDefinition: RuleDefinition[Rule]): Validated[NonEmptyList[RuleDoesNotMeetRequirement], Unit] = {
    ruleDefinition match {
      case RuleDefinition(_, NotUsingVariable(), _, _) => Validated.Valid(())
      case RuleDefinition(rule, usingVariable: UsingVariable[Rule], _, _) =>
        val allNonCompliantResults = RequirementVerifier.verify(rule, usingVariable, allRules).collect { case r: ComplianceResult.NonCompliantWith => r }
        allNonCompliantResults match {
          case Nil => Validated.Valid(())
          case head :: tail => Validated.Invalid(NonEmptyList(head, tail).map(RuleDoesNotMeetRequirement.apply))
        }
    }
  }

  sealed trait BlockValidationError
  object BlockValidationError {
    case object AuthorizationWithoutAuthentication extends BlockValidationError
    final case class OnlyOneAuthenticationRuleAllowed(authRules: NonEmptyList[AuthenticationRule]) extends BlockValidationError
    sealed trait KibanaRuleTogetherWith extends BlockValidationError
    object KibanaRuleTogetherWith {
      case object ActionsRule extends KibanaRuleTogetherWith
      case object FilterRule extends KibanaRuleTogetherWith
      case object FieldsRule extends KibanaRuleTogetherWith
      case object ResponseFieldsRule extends KibanaRuleTogetherWith
    }
    final case class RuleDoesNotMeetRequirement(nonCompliant: ComplianceResult.NonCompliantWith) extends BlockValidationError
    sealed trait KibanaUserDataRuleTogetherWith extends BlockValidationError
    object KibanaUserDataRuleTogetherWith {
      case object KibanaAccessRule extends KibanaUserDataRuleTogetherWith
      case object KibanaIndexRule extends KibanaUserDataRuleTogetherWith
      case object KibanaTemplateIndexRule extends KibanaUserDataRuleTogetherWith
      case object KibanaHideAppsRule extends KibanaUserDataRuleTogetherWith
    }
  }

}

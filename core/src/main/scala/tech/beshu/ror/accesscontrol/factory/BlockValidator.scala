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
import tech.beshu.ror.accesscontrol.blocks.Block.RuleDefinition
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.{AuthenticationRule, AuthorizationRule}
import tech.beshu.ror.accesscontrol.blocks.rules.auth.{GroupsAllOfRule, GroupsAnyOfRule}
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.{ActionsRule, FieldsRule, FilterRule, ResponseFieldsRule}
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.*
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.RequirementVerifier
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.UsageRequirement.ComplianceResult
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.VariableUsage.{NotUsingVariable, UsingVariable}
import tech.beshu.ror.accesscontrol.factory.BlockValidator.BlockValidationError.{KibanaRuleTogetherWith, KibanaUserDataRuleTogetherWith, RuleDoesNotMeetRequirement}
import tech.beshu.ror.implicits.*

object BlockValidator {

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
        case _: GroupsAnyOfRule => false
        case _: GroupsAllOfRule => false
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
    if(isKibanaRelated(ruleDefs)) {
      (
        validateIfKibanaUserDataRuleIsNotUsedWithOldDeprecatedKibanaRules(ruleDefs),
        validateIfKibanaRelatedRulesCoexistenceWithOther(ruleDefs)
      ).mapN { case _ => () }
    } else {
      Validated.Valid(())
    }
  }

  private def isKibanaRelated(ruleDefs: NonEmptyList[RuleDefinition[Rule]]) = {
    ruleDefs.exists(_.rule match {
      case _: KibanaRelatedRule => true
      case _ => false
    })
  }

  private def validateIfKibanaRelatedRulesCoexistenceWithOther(rules: NonEmptyList[RuleDefinition[Rule]]) = {
    if(isKibanaRelated(rules)) {
      NonEmptyList
        .fromList {
          rules
            .map(_.rule)
            .collect[BlockValidationError] {
              case _: ActionsRule => KibanaRuleTogetherWith.ActionsRule
              case _: FilterRule => KibanaRuleTogetherWith.FilterRule
              case _: FieldsRule => KibanaRuleTogetherWith.FieldsRule
              case _: ResponseFieldsRule => KibanaRuleTogetherWith.ResponseFieldsRule
            }
        } match {
        case None => Validated.Valid(())
        case Some(errors) => Validated.Invalid(errors)
      }
    } else {
      Validated.Valid(())
    }
  }

  private def validateIfKibanaUserDataRuleIsNotUsedWithOldDeprecatedKibanaRules(ruleDefs: NonEmptyList[RuleDefinition[Rule]]) = {
    if(containsKibanaUserDataRule(ruleDefs)) {
      NonEmptyList
        .fromList {
          ruleDefs
            .map(_.rule)
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

  private def containsKibanaUserDataRule(ruleDefs: NonEmptyList[RuleDefinition[Rule]]) = {
    ruleDefs.exists(_.rule match {
      case _: KibanaUserDataRule => true
      case _ => false
    })
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

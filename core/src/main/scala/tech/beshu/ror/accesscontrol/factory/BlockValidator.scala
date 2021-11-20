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

import cats.data.Validated._
import cats.data.{NonEmptyList, Validated, _}
import cats.syntax.all._
import tech.beshu.ror.accesscontrol.blocks.Block.RuleWithVariableUsageDefinition
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.base.Rule.{AuthenticationRule, AuthorizationRule}
import tech.beshu.ror.accesscontrol.blocks.rules.{ActionsRule, GroupsRule, KibanaAccessRule}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.RequirementVerifier
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.UsageRequirement.ComplianceResult
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.VariableUsage.{NotUsingVariable, UsingVariable}
import tech.beshu.ror.accesscontrol.factory.BlockValidator.BlockValidationError.RuleDoesNotMeetRequirement

object BlockValidator {

  def validate(rules: NonEmptyList[RuleWithVariableUsageDefinition[Rule]]): ValidatedNel[BlockValidationError, Unit] = {
    (
      validateAuthorizationWithAuthenticationPrinciple(rules),
      validateOnlyOneAuthenticationRulePrinciple(rules),
      validateKibanaAccessRuleAndActionsRuleSeparationPrinciple(rules),
      validateRequirementsForRulesUsingVariables(rules)
    ).mapN { case _ => () }
  }

  private def validateAuthorizationWithAuthenticationPrinciple(rules: NonEmptyList[RuleWithVariableUsageDefinition[Rule]]): ValidatedNel[BlockValidationError, Unit] = {
    rules.find(_.rule.isInstanceOf[AuthorizationRule]) match {
      case None => Validated.Valid(())
      case Some(_) if rules.exists(_.rule.isInstanceOf[AuthenticationRule]) => Validated.Valid(())
      case Some(_) => Validated.Invalid(NonEmptyList.one(BlockValidationError.AuthorizationWithoutAuthentication))
    }
  }

  private def validateOnlyOneAuthenticationRulePrinciple(rules: NonEmptyList[RuleWithVariableUsageDefinition[Rule]]) = {
    rules
      .map(_.rule)
      .collect { case a: AuthenticationRule => a }
      .filter {
        case _: GroupsRule => false
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

  private def validateKibanaAccessRuleAndActionsRuleSeparationPrinciple(rules: NonEmptyList[RuleWithVariableUsageDefinition[Rule]]): ValidatedNel[BlockValidationError, Unit] = {
    val kibanaAccessRules = rules.map(_.rule).collect { case r: KibanaAccessRule => r }
    val actionsRules = rules.map(_.rule).collect { case r: ActionsRule => r }
    (kibanaAccessRules, actionsRules) match {
      case (Nil, Nil) => Validated.Valid(())
      case (Nil, _) => Validated.Valid(())
      case (_, Nil) => Validated.Valid(())
      case (_, _) => Validated.Invalid(NonEmptyList.one(BlockValidationError.KibanaAccessRuleTogetherWithActionsRule))
    }
  }

  private def validateRequirementsForRulesUsingVariables(allRules: NonEmptyList[RuleWithVariableUsageDefinition[Rule]]): ValidatedNel[BlockValidationError, Unit] = {
    allRules.toList
      .map(validateRequirementsForSingleRule(allRules.map(_.rule))) match {
      case Nil => Validated.Valid(())
      case head :: tail => NonEmptyList(head, tail).sequence_
    }
  }

  private def validateRequirementsForSingleRule(allRules: NonEmptyList[Rule])
                                               (ruleDefinition: RuleWithVariableUsageDefinition[Rule]): Validated[NonEmptyList[RuleDoesNotMeetRequirement], Unit] = {
    ruleDefinition match {
      case RuleWithVariableUsageDefinition(_, NotUsingVariable) => Validated.Valid(())
      case RuleWithVariableUsageDefinition(rule, usingVariable: UsingVariable[Rule]) =>
        val allNonCompliantResults = RequirementVerifier.verify(rule, usingVariable, allRules).collect { case r: ComplianceResult.NonCompliantWith => r }
        allNonCompliantResults match {
          case Nil => Validated.Valid(())
          case head :: tail => Validated.Invalid(NonEmptyList(head, tail).map(RuleDoesNotMeetRequirement))
        }
    }
  }

  sealed trait BlockValidationError
  object BlockValidationError {
    case object AuthorizationWithoutAuthentication extends BlockValidationError
    final case class OnlyOneAuthenticationRuleAllowed(authRules: NonEmptyList[AuthenticationRule]) extends BlockValidationError
    case object KibanaAccessRuleTogetherWithActionsRule extends BlockValidationError
    final case class RuleDoesNotMeetRequirement(nonCompliant: ComplianceResult.NonCompliantWith) extends BlockValidationError
  }

}

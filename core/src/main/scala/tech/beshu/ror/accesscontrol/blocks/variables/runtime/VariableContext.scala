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
package tech.beshu.ror.accesscontrol.blocks.variables.runtime

import cats.data.NonEmptyList
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.MultiExtractable.SingleExtractableWrapper

object VariableContext {

  sealed trait VariableType

  object VariableType {
    trait User extends VariableType
    trait CurrentGroup extends VariableType
    trait Header extends VariableType
    trait Jwt extends VariableType
  }

  trait UsingVariable {
    this: Rule =>
    def usedVariables: NonEmptyList[RuntimeResolvableVariable[_]]

    def extractVariablesTypesFromExtractables: List[VariableType] = {
      usedVariables.toList
        .flatMap {
          case RuntimeSingleResolvableVariable.ToBeResolved(extractables) => extractSingle(extractables)
          case RuntimeMultiResolvableVariable.ToBeResolved(extractables) => extractMulti(extractables)
          case _ => List.empty
        }
    }

    private def extractSingle(extractables: NonEmptyList[SingleExtractable]) =
      extractables.collect { case e: VariableContext.VariableType => e }

    private def extractMulti(extractables: NonEmptyList[MultiExtractable]) =
      extractables.collect {
        case SingleExtractableWrapper(extractable: VariableContext.VariableType) => extractable
        case e: VariableContext.VariableType => e
      }
  }

  sealed trait UsageRequirement {
    def checkIfComplies(rulesBefore: List[Rule]): UsageRequirement.ComplianceResult
  }

  object UsageRequirement {

    sealed trait ComplianceResult
    object ComplianceResult {
      case object Compliant extends ComplianceResult
      final case class NonCompliantWith(usageRequirement: UsageRequirement) extends ComplianceResult
    }

    def definedFor(variableType: VariableType): Option[UsageRequirement] = {
      variableType match {
        case v: VariableType.User => Some(UsageRequirement.OneOfRuleBeforeMustBeAuthenticationRule(v))
        case v: VariableType.CurrentGroup => Some(UsageRequirement.OneOfRuleBeforeMustBeAuthorizationRule(v))
        case _: VariableType.Header => None
        case _: VariableType.Jwt => None
      }
    }

    final case class OneOfRuleBeforeMustBeAuthenticationRule(requiredBy: VariableType) extends UsageRequirement {
      override def checkIfComplies(rulesBefore: List[Rule]): ComplianceResult =
        rulesBefore.collect { case rule: Rule.AuthenticationRule => rule } match {
          case Nil => ComplianceResult.NonCompliantWith(this)
          case _ => ComplianceResult.Compliant
        }
    }

    final case class OneOfRuleBeforeMustBeAuthorizationRule(requiredBy: VariableType) extends UsageRequirement {
      override def checkIfComplies(rulesBefore: List[Rule]): ComplianceResult =
        rulesBefore.collect { case rule: Rule.AuthorizationRule => rule } match {
          case Nil => ComplianceResult.NonCompliantWith(this)
          case _ => ComplianceResult.Compliant
        }
    }
  }

  object RequirementVerifier {

    def verify[A <: Rule with UsingVariable](verifiedRule: A, otherRules: NonEmptyList[Rule]) = {
      val rulesBefore = findRulesListedBeforeGivenRule(verifiedRule, otherRules)
      verifiedRule.extractVariablesTypesFromExtractables
        .flatMap(checkSingleVariableBasedOn(rulesBefore))
    }

    private def findRulesListedBeforeGivenRule[A <: Rule with UsingVariable](rule: A, otherRules: NonEmptyList[Rule]) =
      otherRules.toList.takeWhile(_ != rule)

    private def checkSingleVariableBasedOn(rulesBefore: List[Rule])(usedVariable: VariableType) = {
      UsageRequirement.definedFor(usedVariable)
        .map(_.checkIfComplies(rulesBefore))
    }
  }
}

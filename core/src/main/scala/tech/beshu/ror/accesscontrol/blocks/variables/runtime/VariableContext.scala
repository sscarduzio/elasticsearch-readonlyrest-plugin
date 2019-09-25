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
import tech.beshu.ror.accesscontrol.blocks.rules.{BaseSpecializedIndicesRule, FilterRule, GroupsRule, HostsRule, IndicesRule, KibanaAccessRule, KibanaIndexRule, LocalHostsRule, Rule, UriRegexRule, UsersRule, XForwardedForRule}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.MultiExtractable.SingleExtractableWrapper

object VariableContext {

  sealed trait VariableType

  object VariableType {
    trait User extends VariableType
    trait CurrentGroup extends VariableType
    trait Header extends VariableType
    trait Jwt extends VariableType
  }

  trait UsingVariable[T <: Rule] {
    def usedVariablesBy(rule: T): List[RuntimeResolvableVariable[_]]
  }

  object UsingVariable {
    implicit val usingVariableRules: UsingVariable[Rule] = {
      case rule: BaseSpecializedIndicesRule => rule.settings.allowedIndices.toNonEmptyList.toList
      case rule: FilterRule => rule.settings.filter :: Nil
      case rule: GroupsRule => rule.settings.groups.toList
      case rule: HostsRule => rule.settings.allowedHosts.toNonEmptyList.toList
      case rule: IndicesRule => rule.settings.allowedIndices.toNonEmptyList.toList
      case rule: KibanaAccessRule => rule.settings.kibanaIndex :: Nil
      case rule: KibanaIndexRule => rule.settings.kibanaIndex :: Nil
      case rule: LocalHostsRule => rule.settings.allowedAddresses.toNonEmptyList.toList
      case rule: UriRegexRule => rule.settings.uriPatterns.toNonEmptyList.toList
      case rule: UsersRule => rule.settings.userIds.toNonEmptyList.toList
      case rule: XForwardedForRule => rule.settings.allowedAddresses.toNonEmptyList.toList
      case _ => List.empty
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

    def verify[A <: Rule : UsingVariable](verifiedRule: A, otherRules: NonEmptyList[Rule]) = {
      val rulesBefore = findRulesListedBeforeGivenRule(verifiedRule, otherRules)
      val usedVariables = implicitly[UsingVariable[A]].usedVariablesBy(verifiedRule)
      extractVariablesTypesFromExtractables(usedVariables)
        .flatMap(checkSingleVariableBasedOn(rulesBefore))
    }

    private def findRulesListedBeforeGivenRule[A <: Rule: UsingVariable](rule: A, otherRules: NonEmptyList[Rule]) =
      otherRules.toList.takeWhile(_ != rule)

    private def checkSingleVariableBasedOn(rulesBefore: List[Rule])(usedVariable: VariableType) = {
      UsageRequirement.definedFor(usedVariable)
        .map(_.checkIfComplies(rulesBefore))
    }

    private def extractVariablesTypesFromExtractables(usedVariables: List[RuntimeResolvableVariable[_]]): List[VariableType] = {
      usedVariables
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
}

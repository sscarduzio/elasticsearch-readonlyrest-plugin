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
import tech.beshu.ror.accesscontrol.blocks.rules.{AuthKeyPBKDF2WithHmacSHA512Rule, _}
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.MultiExtractable.SingleExtractableWrapper
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.VariableContext.VariableUsage.UsingVariable

object VariableContext {

  sealed trait VariableType

  object VariableType {
    trait User extends VariableType
    trait CurrentGroup extends VariableType
    trait AvailableGroups extends VariableType
    trait Header extends VariableType
    trait Jwt extends VariableType
  }

  sealed trait VariableUsage[+T <: Rule]

  object VariableUsage {
    trait UsingVariable[T <: Rule] extends VariableUsage[T] {
      def usedVariablesBy(rule: T): NonEmptyList[RuntimeResolvableVariable[_]]
    }
    object UsingVariable {
      def apply[T <: Rule](f: T => NonEmptyList[RuntimeResolvableVariable[_]]): UsingVariable[T] = f(_)
    }

    case object NotUsingVariable extends VariableUsage[Nothing]

    implicit val filterRule: VariableUsage[FilterRule] = UsingVariable[FilterRule](rule => NonEmptyList.one(rule.settings.filter))
    implicit val groupsRule: VariableUsage[GroupsRule] = UsingVariable[GroupsRule](rule => rule.settings.groups.toNonEmptyList)
    implicit val hostsRule: VariableUsage[HostsRule] = UsingVariable[HostsRule](rule => rule.settings.allowedHosts.toNonEmptyList)
    implicit val indicesRule: VariableUsage[IndicesRule] = UsingVariable[IndicesRule](rule => rule.settings.allowedIndices.toNonEmptyList)
    implicit val kibanaAccessRule: VariableUsage[KibanaAccessRule] = UsingVariable[KibanaAccessRule](rule => NonEmptyList.one(rule.settings.kibanaIndex))
    implicit val kibanaIndexRule: VariableUsage[KibanaIndexRule] = UsingVariable[KibanaIndexRule](rule => NonEmptyList.one(rule.settings.kibanaIndex))
    implicit val kibanaTemplateIndexRule: VariableUsage[KibanaTemplateIndexRule] = UsingVariable[KibanaTemplateIndexRule](rule => NonEmptyList.one(rule.settings.kibanaTemplateIndex))
    implicit val localHostsRule: VariableUsage[LocalHostsRule] = UsingVariable[LocalHostsRule](rule => rule.settings.allowedAddresses.toNonEmptyList)
    implicit val repositoriesRule: VariableUsage[RepositoriesRule] = UsingVariable[RepositoriesRule](rule => rule.settings.allowedRepositories.toNonEmptyList)
    implicit val snapshotsRule: VariableUsage[SnapshotsRule] = UsingVariable[SnapshotsRule](rule => rule.settings.allowedSnapshots.toNonEmptyList)
    implicit val uriRegexRule: VariableUsage[UriRegexRule] = UsingVariable[UriRegexRule](rule => rule.settings.uriPatterns.toNonEmptyList)
    implicit val usersRule:  VariableUsage[UsersRule] = UsingVariable[UsersRule](rule => rule.settings.userIds.toNonEmptyList)
    implicit val xForwarderForRule: VariableUsage[XForwardedForRule] = UsingVariable[XForwardedForRule](rule => rule.settings.allowedAddresses.toNonEmptyList)

    implicit val actionsRule: VariableUsage[ActionsRule] = NotUsingVariable
    implicit val apiKeyRule: VariableUsage[ApiKeysRule] = NotUsingVariable
    implicit val authKeySha1Rule: VariableUsage[AuthKeySha1Rule] = NotUsingVariable
    implicit val authKeySha256Rule: VariableUsage[AuthKeySha256Rule] = NotUsingVariable
    implicit val authKeySha512Rule: VariableUsage[AuthKeySha512Rule] = NotUsingVariable
    implicit val authKeyPBKDF2WithHmacSHA512Rule: VariableUsage[AuthKeyPBKDF2WithHmacSHA512Rule] = NotUsingVariable
    implicit val authKeyRule: VariableUsage[AuthKeyRule] = NotUsingVariable
    implicit val authKeyUnixRule: VariableUsage[AuthKeyUnixRule] = NotUsingVariable
    implicit val externalAuthenticationRule: VariableUsage[ExternalAuthenticationRule] = NotUsingVariable
    implicit val externalAuthorizationRule: VariableUsage[ExternalAuthorizationRule] = NotUsingVariable
    implicit val fieldsRule: VariableUsage[FieldsRule] = NotUsingVariable
    implicit val headersAndRule: VariableUsage[HeadersAndRule] = NotUsingVariable
    implicit val headersOrRule: VariableUsage[HeadersOrRule] = NotUsingVariable
    implicit val jwtAuthRule: VariableUsage[JwtAuthRule] = NotUsingVariable
    implicit val kibanaHideAppsRule: VariableUsage[KibanaHideAppsRule] = NotUsingVariable
    implicit val ldapAuthenticationRule: VariableUsage[LdapAuthenticationRule] = NotUsingVariable
    implicit val ldapAuthorizationRule: VariableUsage[LdapAuthorizationRule] = NotUsingVariable
    implicit val ldapAuthRule: VariableUsage[LdapAuthRule] = NotUsingVariable
    implicit val maxBodyLengthRule: VariableUsage[MaxBodyLengthRule] = NotUsingVariable
    implicit val methodsRule: VariableUsage[MethodsRule] = NotUsingVariable
    implicit val proxyAuthRule: VariableUsage[ProxyAuthRule] = NotUsingVariable
    implicit val rorKbnAuthRule: VariableUsage[RorKbnAuthRule] = NotUsingVariable
    implicit val sessionMaxIdleRule: VariableUsage[SessionMaxIdleRule] = NotUsingVariable
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
        case _: VariableType.CurrentGroup => None
        case _: VariableType.AvailableGroups => None
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
  }

  object RequirementVerifier {

    def verify[A <: Rule](verifiedRule: A, usingVariable: UsingVariable[A], otherRules: NonEmptyList[Rule]): List[UsageRequirement.ComplianceResult] = {
      val rulesBefore = findRulesListedBeforeGivenRule(verifiedRule, otherRules)
      val usedVariables = usingVariable.usedVariablesBy(verifiedRule)
      extractVariablesTypesFromExtractables(usedVariables)
        .flatMap(checkSingleVariableBasedOn(rulesBefore))
    }

    private def findRulesListedBeforeGivenRule[A <: Rule](rule: A, otherRules: NonEmptyList[Rule]) =
      otherRules.toList.takeWhile(_ != rule)

    private def checkSingleVariableBasedOn(rulesBefore: List[Rule])(usedVariable: VariableType) = {
      UsageRequirement.definedFor(usedVariable)
        .map(_.checkIfComplies(rulesBefore))
    }

    private def extractVariablesTypesFromExtractables(usedVariables: NonEmptyList[RuntimeResolvableVariable[_]]): List[VariableType] = {
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
}

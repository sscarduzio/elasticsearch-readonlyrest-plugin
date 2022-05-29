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
package tech.beshu.ror.accesscontrol.blocks

import cats.implicits.catsSyntaxOptionId
import eu.timepit.refined.auto._
import eu.timepit.refined.types.string.NonEmptyString
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.rules.AuthKeyHashingRule.HashedCredentials
import tech.beshu.ror.accesscontrol.blocks.rules._
import tech.beshu.ror.accesscontrol.blocks.rules.base._
import tech.beshu.ror.accesscontrol.blocks.rules.base.impersonation.Impersonation
import tech.beshu.ror.accesscontrol.blocks.rules.indicesrule.IndicesRule

final case class ImpersonationWarning(block: Block.Name,
                                      ruleName: Rule.Name,
                                      message: NonEmptyString,
                                      hint: String)

object ImpersonationWarning {
  sealed trait ImpersonationWarningSupport[+T <: Rule]

  object ImpersonationWarningSupport {
    trait WithPossibleWarnings[T <: Rule] extends ImpersonationWarningSupport[T] {
      def warningFor(rule: T, blockName: Block.Name)
                    (implicit requestId: RequestId): Option[ImpersonationWarning]
    }

    object WithPossibleWarnings {
      def apply[T <: Rule](f: (T, Block.Name, RequestId) => Option[ImpersonationWarning]): WithPossibleWarnings[T] =
        new WithPossibleWarnings[T] {
          override def warningFor(rule: T, blockName: Block.Name)
                                 (implicit requestId: RequestId): Option[ImpersonationWarning] = f(rule, blockName, requestId)
        }
    }

    object WithoutWarnings extends ImpersonationWarningSupport[Nothing]

    implicit val actionsRule: ImpersonationWarningSupport[ActionsRule] = WithoutWarnings
    implicit val apiKeyRule: ImpersonationWarningSupport[ApiKeysRule] = WithoutWarnings
    implicit val authKeyRule: ImpersonationWarningSupport[AuthKeyRule] = WithoutWarnings
    implicit val authKeyUnixRule: ImpersonationWarningSupport[AuthKeyUnixRule] = WithoutWarnings
    implicit val fieldsRule: ImpersonationWarningSupport[FieldsRule] = WithoutWarnings
    implicit val filterRule: ImpersonationWarningSupport[FilterRule] = WithoutWarnings
    implicit val groupsOrRule: ImpersonationWarningSupport[GroupsOrRule] = WithoutWarnings
    implicit val groupsAndRule: ImpersonationWarningSupport[GroupsAndRule] = WithoutWarnings
    implicit val headersAndRule: ImpersonationWarningSupport[HeadersAndRule] = WithoutWarnings
    implicit val headersOrRule: ImpersonationWarningSupport[HeadersOrRule] = WithoutWarnings
    implicit val hostsRule: ImpersonationWarningSupport[HostsRule] = WithoutWarnings
    implicit val indicesRule: ImpersonationWarningSupport[IndicesRule] = WithoutWarnings
    implicit val kibanaAccessRule: ImpersonationWarningSupport[KibanaAccessRule] = WithoutWarnings
    implicit val kibanaHideAppsRule: ImpersonationWarningSupport[KibanaHideAppsRule] = WithoutWarnings
    implicit val kibanaIndexRule: ImpersonationWarningSupport[KibanaIndexRule] = WithoutWarnings
    implicit val kibanaTemplateIndexRule: ImpersonationWarningSupport[KibanaTemplateIndexRule] = WithoutWarnings
    implicit val localHostsRule: ImpersonationWarningSupport[LocalHostsRule] = WithoutWarnings
    implicit val maxBodyLengthRule: ImpersonationWarningSupport[MaxBodyLengthRule] = WithoutWarnings
    implicit val methodsRule: ImpersonationWarningSupport[MethodsRule] = WithoutWarnings
    implicit val proxyAuthRule: ImpersonationWarningSupport[ProxyAuthRule] = WithoutWarnings
    implicit val repositoriesRule: ImpersonationWarningSupport[RepositoriesRule] = WithoutWarnings
    implicit val responseFieldsRule: ImpersonationWarningSupport[ResponseFieldsRule] = WithoutWarnings
    implicit val sessionMaxIdleRule: ImpersonationWarningSupport[SessionMaxIdleRule] = WithoutWarnings
    implicit val snapshotsRule: ImpersonationWarningSupport[SnapshotsRule] = WithoutWarnings
    implicit val uriRegexRule: ImpersonationWarningSupport[UriRegexRule] = WithoutWarnings
    implicit val usersRule: ImpersonationWarningSupport[UsersRule] = WithoutWarnings
    implicit val xForwarderForRule: ImpersonationWarningSupport[XForwardedForRule] = WithoutWarnings

    implicit val authKeyPBKDF2WithHmacSHA512Rule: ImpersonationWarningSupport[AuthKeyPBKDF2WithHmacSHA512Rule] = WithPossibleWarnings[AuthKeyPBKDF2WithHmacSHA512Rule](fromHashedCredentials)
    implicit val authKeySha1Rule: ImpersonationWarningSupport[AuthKeySha1Rule] = WithPossibleWarnings[AuthKeySha1Rule](fromHashedCredentials)
    implicit val authKeySha256Rule: ImpersonationWarningSupport[AuthKeySha256Rule] = WithPossibleWarnings[AuthKeySha256Rule](fromHashedCredentials)
    implicit val authKeySha512Rule: ImpersonationWarningSupport[AuthKeySha512Rule] = WithPossibleWarnings[AuthKeySha512Rule](fromHashedCredentials)
    implicit val externalAuthenticationRule: ImpersonationWarningSupport[ExternalAuthenticationRule] = WithPossibleWarnings[ExternalAuthenticationRule]((rule, blockName, requestId) =>
      for {
        mocksProvider <- mocksProvider(rule.impersonation)
        warning <- mocksProvider.externalAuthenticationServiceWith(rule.settings.service.id)(requestId) match {
          case Some(_) => None
          case None =>
            ImpersonationWarning(
              block = blockName,
              ruleName = rule.name,
              message = impersonationNotSupportedWithoutMockMsg(rule.name),
              hint = s"Configure a mock of an external authentication service with ID [${rule.settings.service.id.value.value}]"
            ).some
        }
      } yield warning
    )
    implicit val externalAuthorizationRule: ImpersonationWarningSupport[ExternalAuthorizationRule] = WithPossibleWarnings[ExternalAuthorizationRule]((rule, blockName, requestId) =>
      for {
        mocksProvider <- mocksProvider(rule.impersonation)
        warning <- mocksProvider.externalAuthorizationServiceWith(rule.settings.service.id)(requestId) match {
          case Some(_) => None
          case None =>
            ImpersonationWarning(
              block = blockName,
              ruleName = rule.name,
              message = impersonationNotSupportedWithoutMockMsg(rule.name),
              hint = s"Configure a mock of an external authorization service with ID [${rule.settings.service.id.value.value}]"
            ).some
        }
      } yield warning
    )
    implicit val jwtAuthRule: ImpersonationWarningSupport[JwtAuthRule] = WithPossibleWarnings[JwtAuthRule] { (rule, blockName, _) =>
      Some(impersonationNotSupportedWarning(rule, blockName))
    }
    implicit val ldapAuthenticationRule: ImpersonationWarningSupport[LdapAuthenticationRule] = WithPossibleWarnings[LdapAuthenticationRule] { (rule, blockName, requestId) =>
      ldapWarning(rule.name, blockName, rule.settings.ldap.id, rule.impersonation)(requestId)
    }
    implicit val ldapAuthorizationRule: ImpersonationWarningSupport[LdapAuthorizationRule] = WithPossibleWarnings[LdapAuthorizationRule] { (rule, blockName, requestId) =>
      ldapWarning(rule.name, blockName, rule.settings.ldap.id, rule.impersonation)(requestId)
    }
    implicit val ldapAuthRule: ImpersonationWarningSupport[LdapAuthRule] = WithPossibleWarnings[LdapAuthRule] { (rule, blockName, requestId) =>
      ldapWarning(rule.name, blockName, rule.authorization.settings.ldap.id, rule.authorization.impersonation)(requestId)
    }
    implicit val rorKbnAuthRule: ImpersonationWarningSupport[RorKbnAuthRule] = WithPossibleWarnings[RorKbnAuthRule] { (rule, blockName, _) =>
      Some(impersonationNotSupportedWarning(rule, blockName))
    }

    private def fromHashedCredentials[R <: BasicAuthenticationRule[HashedCredentials]](rule: R, blockName: Block.Name, requestId: RequestId): Option[ImpersonationWarning] = {
      rule.settings.credentials match {
        case _: HashedCredentials.HashedUserAndPassword =>
          Some(ImpersonationWarning(
            block = blockName,
            ruleName = rule.name,
            message = "The rule contains fully hashed username and password. It doesn't support impersonation in this configuration",
            hint = s"You can use second version of the rule and use not hashed username. Like that: `${rule.name.value}: USER_NAME:hash(PASSWORD)"
          ))
        case _: HashedCredentials.HashedOnlyPassword =>
          None
      }
    }

    private def impersonationNotSupportedWarning[R <: Rule](rule: R, blockName: Block.Name) =
      ImpersonationWarning(
        block = blockName,
        ruleName = rule.name,
        message = "Impersonation is not supported by this rule by default",
        hint = ""
      )

    private def ldapWarning(ruleName: Rule.Name,
                            blockName: Block.Name,
                            serviceId: LdapService#Id,
                            impersonation: Impersonation)
                           (implicit requestId: RequestId): Option[ImpersonationWarning] = {
      for {
        mocksProvider <- mocksProvider(impersonation)
        warning <- mocksProvider.ldapServiceWith(serviceId) match {
          case Some(_) => None
          case None =>
            ImpersonationWarning(
              block = blockName,
              ruleName = ruleName,
              message = impersonationNotSupportedWithoutMockMsg(ruleName),
              hint = s"Configure a mock of an LDAP service with ID [${serviceId.value.value}]"
            ).some
        }
      } yield warning
    }

    private def mocksProvider(impersonation: Impersonation) = impersonation match {
      case Impersonation.Enabled(settings) => Some(settings.mocksProvider)
      case Impersonation.Disabled => None
    }

    private def impersonationNotSupportedWithoutMockMsg(ruleName: Rule.Name): NonEmptyString = NonEmptyString.unsafeFrom(
      s"The rule '${ruleName.value}' will not match during impersonation until a mock of service is not configured"
    )
  }
}

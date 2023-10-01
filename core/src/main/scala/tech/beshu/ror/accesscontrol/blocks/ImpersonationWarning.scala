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
import tech.beshu.ror.accesscontrol.blocks.ImpersonationWarning.ImpersonationWarningSupport.ImpersonationWarningExtractor.noWarnings
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.LdapService
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.AuthKeyHashingRule.HashedCredentials
import tech.beshu.ror.accesscontrol.blocks.rules.auth._
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BasicAuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.impersonation.{Impersonation, ImpersonationSupport}
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch._
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.IndicesRule
import tech.beshu.ror.accesscontrol.blocks.rules.http._
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.{KibanaAccessRule, KibanaHideAppsRule, KibanaIndexRule, KibanaTemplateIndexRule, KibanaUserDataRule}
import tech.beshu.ror.accesscontrol.blocks.rules.tranport.{HostsRule, LocalHostsRule}

import scala.annotation.nowarn

final case class ImpersonationWarning(block: Block.Name,
                                      ruleName: Rule.Name,
                                      message: NonEmptyString,
                                      hint: String)

object ImpersonationWarning {
  sealed trait ImpersonationWarningSupport[+T <: Rule]

  object ImpersonationWarningSupport {
    object NotSupported extends ImpersonationWarningSupport[Nothing]

    trait ImpersonationWarningExtractor[T <: Rule with ImpersonationSupport] extends ImpersonationWarningSupport[T] {
      def warningFor(rule: T, blockName: Block.Name)
                    (implicit requestId: RequestId): Option[ImpersonationWarning]
    }

    object ImpersonationWarningExtractor {
      def apply[T <: Rule with ImpersonationSupport](f: (T, Block.Name, RequestId) => Option[ImpersonationWarning]): ImpersonationWarningExtractor[T] =
        new ImpersonationWarningExtractor[T] {
          override def warningFor(rule: T, blockName: Block.Name)
                                 (implicit requestId: RequestId): Option[ImpersonationWarning] = f(rule, blockName, requestId)
        }

      def noWarnings[T <: Rule with ImpersonationSupport]: ImpersonationWarningExtractor[T] = {
        new ImpersonationWarningExtractor[T] {
          override final def warningFor(rule: T, blockName: Block.Name)
                                       (implicit requestId: RequestId): Option[ImpersonationWarning] = None
        }
      }
    }

    implicit val actionsRule: ImpersonationWarningSupport[ActionsRule] = NotSupported
    implicit val apiKeyRule: ImpersonationWarningSupport[ApiKeysRule] = NotSupported
    implicit val dataStreamsRule: ImpersonationWarningSupport[DataStreamsRule] = NotSupported
    implicit val fieldsRule: ImpersonationWarningSupport[FieldsRule] = NotSupported
    implicit val filterRule: ImpersonationWarningSupport[FilterRule] = NotSupported
    implicit val headersAndRule: ImpersonationWarningSupport[HeadersAndRule] = NotSupported
    implicit val headersOrRule: ImpersonationWarningSupport[HeadersOrRule] = NotSupported
    implicit val hostsRule: ImpersonationWarningSupport[HostsRule] = NotSupported
    implicit val indicesRule: ImpersonationWarningSupport[IndicesRule] = NotSupported
    implicit val kibanaUserDataRule: ImpersonationWarningSupport[KibanaUserDataRule] = NotSupported
    implicit val kibanaAccessRule: ImpersonationWarningSupport[KibanaAccessRule] = NotSupported
    implicit val kibanaHideAppsRule: ImpersonationWarningSupport[KibanaHideAppsRule] = NotSupported
    implicit val kibanaIndexRule: ImpersonationWarningSupport[KibanaIndexRule] = NotSupported
    implicit val kibanaTemplateIndexRule: ImpersonationWarningSupport[KibanaTemplateIndexRule] = NotSupported
    implicit val localHostsRule: ImpersonationWarningSupport[LocalHostsRule] = NotSupported
    implicit val maxBodyLengthRule: ImpersonationWarningSupport[MaxBodyLengthRule] = NotSupported
    implicit val methodsRule: ImpersonationWarningSupport[MethodsRule] = NotSupported
    implicit val repositoriesRule: ImpersonationWarningSupport[RepositoriesRule] = NotSupported
    implicit val responseFieldsRule: ImpersonationWarningSupport[ResponseFieldsRule] = NotSupported
    implicit val sessionMaxIdleRule: ImpersonationWarningSupport[SessionMaxIdleRule] = NotSupported
    implicit val snapshotsRule: ImpersonationWarningSupport[SnapshotsRule] = NotSupported
    implicit val uriRegexRule: ImpersonationWarningSupport[UriRegexRule] = NotSupported
    implicit val usersRule: ImpersonationWarningSupport[UsersRule] = NotSupported
    implicit val xForwarderForRule: ImpersonationWarningSupport[XForwardedForRule] = NotSupported

    implicit val authKeyPBKDF2WithHmacSHA512Rule: ImpersonationWarningExtractor[AuthKeyPBKDF2WithHmacSHA512Rule] = ImpersonationWarningExtractor[AuthKeyPBKDF2WithHmacSHA512Rule](fromHashedCredentials)
    implicit val authKeyRule: ImpersonationWarningExtractor[AuthKeyRule] = noWarnings[AuthKeyRule]
    implicit val authKeyUnixRule: ImpersonationWarningExtractor[AuthKeyUnixRule] = noWarnings[AuthKeyUnixRule]
    implicit val authKeySha1Rule: ImpersonationWarningExtractor[AuthKeySha1Rule] = ImpersonationWarningExtractor[AuthKeySha1Rule](fromHashedCredentials)
    implicit val authKeySha256Rule: ImpersonationWarningExtractor[AuthKeySha256Rule] = ImpersonationWarningExtractor[AuthKeySha256Rule](fromHashedCredentials)
    implicit val authKeySha512Rule: ImpersonationWarningExtractor[AuthKeySha512Rule] = ImpersonationWarningExtractor[AuthKeySha512Rule](fromHashedCredentials)
    implicit val externalAuthenticationRule: ImpersonationWarningExtractor[ExternalAuthenticationRule] = ImpersonationWarningExtractor[ExternalAuthenticationRule]((rule, blockName, requestId) =>
      for {
        mocksProvider <- mocksProvider(rule.impersonation)
        serviceId = rule.settings.service.id
        warning <- mocksProvider.externalAuthenticationServiceWith(serviceId)(requestId) match {
          case Some(_) => None
          case None =>
            ImpersonationWarning(
              block = blockName,
              ruleName = rule.name,
              message = impersonationNotSupportedWithoutMockMsg(rule.name, serviceId.value.value),
              hint = s"Configure a mock of an external authentication service with ID [${serviceId.value.value}]"
            ).some
        }
      } yield warning
    )
    implicit val externalAuthorizationRule: ImpersonationWarningExtractor[ExternalAuthorizationRule] = ImpersonationWarningExtractor[ExternalAuthorizationRule]((rule, blockName, requestId) =>
      for {
        mocksProvider <- mocksProvider(rule.impersonation)
        serviceId = rule.settings.service.id
        warning <- mocksProvider.externalAuthorizationServiceWith(serviceId)(requestId) match {
          case Some(_) => None
          case None =>
            ImpersonationWarning(
              block = blockName,
              ruleName = rule.name,
              message = impersonationNotSupportedWithoutMockMsg(rule.name, serviceId.value.value),
              hint = s"Configure a mock of an external authorization service with ID [${serviceId.value.value}]"
            ).some
        }
      } yield warning
    )
    implicit val groupsOrRule: ImpersonationWarningExtractor[GroupsOrRule] = noWarnings[GroupsOrRule]
    implicit val groupsAndRule: ImpersonationWarningExtractor[GroupsAndRule] = noWarnings[GroupsAndRule]
    implicit val jwtAuthRule: ImpersonationWarningExtractor[JwtAuthRule] = ImpersonationWarningExtractor[JwtAuthRule] { (rule, blockName, _) =>
      Some(impersonationNotSupportedWarning(rule, blockName))
    }
    implicit val ldapAuthenticationRule: ImpersonationWarningExtractor[LdapAuthenticationRule] = ImpersonationWarningExtractor[LdapAuthenticationRule] { (rule, blockName, requestId) =>
      ldapWarning(rule.name, blockName, rule.settings.ldap.id, rule.impersonation)(requestId)
    }
    implicit val ldapAuthorizationRule: ImpersonationWarningExtractor[LdapAuthorizationRule] = ImpersonationWarningExtractor[LdapAuthorizationRule] { (rule, blockName, requestId) =>
      ldapWarning(rule.name, blockName, rule.settings.ldap.id, rule.impersonation)(requestId)
    }
    implicit val ldapAuthRule: ImpersonationWarningExtractor[LdapAuthRule] = ImpersonationWarningExtractor[LdapAuthRule] { (rule, blockName, requestId) =>
      ldapWarning(rule.name, blockName, rule.authorization.settings.ldap.id, rule.authorization.impersonation)(requestId)
    }
    implicit val proxyAuthRule: ImpersonationWarningExtractor[ProxyAuthRule] = noWarnings[ProxyAuthRule]
    implicit val rorKbnAuthRule: ImpersonationWarningExtractor[RorKbnAuthRule] = ImpersonationWarningExtractor[RorKbnAuthRule] { (rule, blockName, _) =>
      Some(impersonationNotSupportedWarning(rule, blockName))
    }
    implicit val tokenAuthenticationRule: ImpersonationWarningExtractor[TokenAuthenticationRule] = noWarnings[TokenAuthenticationRule]

    private def fromHashedCredentials[R <: BasicAuthenticationRule[HashedCredentials]](rule: R,
                                                                                       blockName: Block.Name,
                                                                                       @nowarn("cat=unused") requestId: RequestId): Option[ImpersonationWarning] = {
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
        message = "Impersonation is not supported by this rule",
        hint = "We plan to support it in the future"
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
              message = impersonationNotSupportedWithoutMockMsg(ruleName, serviceId.value.value),
              hint = s"Configure a mock of an LDAP service with ID [${serviceId.value.value}]"
            ).some
        }
      } yield warning
    }

    private def mocksProvider(impersonation: Impersonation) = impersonation match {
      case Impersonation.Enabled(settings) => Some(settings.mocksProvider)
      case Impersonation.Disabled => None
    }

    private def impersonationNotSupportedWithoutMockMsg(ruleName: Rule.Name, serviceName: String): NonEmptyString = NonEmptyString.unsafeFrom(
      s"The rule '${ruleName.value}' will fail to match the impersonating request when the mock of the service '$serviceName' is not configured"
    )
  }
}

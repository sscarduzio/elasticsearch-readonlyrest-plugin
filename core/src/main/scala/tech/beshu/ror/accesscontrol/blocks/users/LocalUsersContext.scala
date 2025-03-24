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
package tech.beshu.ror.accesscontrol.blocks.users

import cats.implicits.*
import cats.kernel.Monoid
import tech.beshu.ror.accesscontrol.blocks.rules.Rule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule
import tech.beshu.ror.accesscontrol.blocks.rules.Rule.AuthenticationRule.EligibleUsersSupport
import tech.beshu.ror.accesscontrol.blocks.rules.auth.*
import tech.beshu.ror.accesscontrol.blocks.rules.auth.base.BaseGroupsRule
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.*
import tech.beshu.ror.accesscontrol.blocks.rules.elasticsearch.indices.*
import tech.beshu.ror.accesscontrol.blocks.rules.http.*
import tech.beshu.ror.accesscontrol.blocks.rules.kibana.*
import tech.beshu.ror.accesscontrol.blocks.rules.tranport.*
import tech.beshu.ror.accesscontrol.blocks.variables.runtime.RuntimeMultiResolvableVariable
import tech.beshu.ror.accesscontrol.domain.GroupsLogic.*
import tech.beshu.ror.accesscontrol.domain.{GroupsLogic, LocalUsers}
import tech.beshu.ror.syntax.*

object LocalUsersContext {

  sealed trait LocalUsersSupport[T <: Rule]

  object LocalUsersSupport {
    trait AvailableLocalUsers[T <: Rule] extends LocalUsersSupport[T] {
      def definedLocalUsers(rule: T): LocalUsers
    }

    object AvailableLocalUsers {
      def apply[T <: Rule](f: T => LocalUsers): AvailableLocalUsers[T] = f(_)
    }

    final case class NotAvailableLocalUsers[T <: Rule]() extends LocalUsersSupport[T]

    implicit val actionsRule: LocalUsersSupport[ActionsRule] = NotAvailableLocalUsers()
    implicit val apiKeyRule: LocalUsersSupport[ApiKeysRule] = NotAvailableLocalUsers()
    implicit val dataStreamsRule: LocalUsersSupport[DataStreamsRule] = NotAvailableLocalUsers()
    implicit val externalAuthenticationRule: LocalUsersSupport[ExternalAuthenticationRule] = NotAvailableLocalUsers()
    implicit val externalAuthorizationRule: LocalUsersSupport[ExternalAuthorizationRule] = NotAvailableLocalUsers()
    implicit val fieldsRule: LocalUsersSupport[FieldsRule] = NotAvailableLocalUsers()
    implicit val filterRule: LocalUsersSupport[FilterRule] = NotAvailableLocalUsers()
    implicit def groupsRule[GL <: GroupsLogic]: LocalUsersSupport[BaseGroupsRule[GL]] = NotAvailableLocalUsers()
    implicit val headersAndRule: LocalUsersSupport[HeadersAndRule] = NotAvailableLocalUsers()
    implicit val headersOrRule: LocalUsersSupport[HeadersOrRule] = NotAvailableLocalUsers()
    implicit val hostsRule: LocalUsersSupport[HostsRule] = NotAvailableLocalUsers()
    implicit val indicesRule: LocalUsersSupport[IndicesRule] = NotAvailableLocalUsers()
    implicit val jwtAuthRule: LocalUsersSupport[JwtAuthRule] = NotAvailableLocalUsers()
    implicit val kibanaUserDataRule: LocalUsersSupport[KibanaUserDataRule] = NotAvailableLocalUsers()
    implicit val kibanaAccessRule: LocalUsersSupport[KibanaAccessRule] = NotAvailableLocalUsers()
    implicit val kibanaHideAppsRule: LocalUsersSupport[KibanaHideAppsRule] = NotAvailableLocalUsers()
    implicit val kibanaIndexRule: LocalUsersSupport[KibanaIndexRule] = NotAvailableLocalUsers()
    implicit val kibanaTemplateIndexRule: LocalUsersSupport[KibanaTemplateIndexRule] = NotAvailableLocalUsers()
    implicit val ldapAuthenticationRule: LocalUsersSupport[LdapAuthenticationRule] = NotAvailableLocalUsers()
    implicit val ldapAuthorizationRule: LocalUsersSupport[LdapAuthorizationRule] = NotAvailableLocalUsers()
    implicit val ldapAuthRule: LocalUsersSupport[LdapAuthRule] = NotAvailableLocalUsers()
    implicit val localHostsRule: LocalUsersSupport[LocalHostsRule] = NotAvailableLocalUsers()
    implicit val maxBodyLengthRule: LocalUsersSupport[MaxBodyLengthRule] = NotAvailableLocalUsers()
    implicit val methodsRule: LocalUsersSupport[MethodsRule] = NotAvailableLocalUsers()
    implicit val repositoriesRule: LocalUsersSupport[RepositoriesRule] = NotAvailableLocalUsers()
    implicit val responseFieldsRule: LocalUsersSupport[ResponseFieldsRule] = NotAvailableLocalUsers()
    implicit val rorKbnAuthRule: LocalUsersSupport[RorKbnAuthRule] = NotAvailableLocalUsers()
    implicit val sessionMaxIdleRule: LocalUsersSupport[SessionMaxIdleRule] = NotAvailableLocalUsers()
    implicit val snapshotsRule: LocalUsersSupport[SnapshotsRule] = NotAvailableLocalUsers()
    implicit val uriRegexRule: LocalUsersSupport[UriRegexRule] = NotAvailableLocalUsers()
    implicit val xForwarderForRule: LocalUsersSupport[XForwardedForRule] = NotAvailableLocalUsers()

    implicit val authKeyRule: LocalUsersSupport[AuthKeyRule] = AvailableLocalUsers[AuthKeyRule](fromEligibleUsers)
    implicit val authKeyPBKDF2WithHmacSHA512Rule: LocalUsersSupport[AuthKeyPBKDF2WithHmacSHA512Rule] = AvailableLocalUsers[AuthKeyPBKDF2WithHmacSHA512Rule](fromEligibleUsers)
    implicit val authKeySha1Rule: LocalUsersSupport[AuthKeySha1Rule] = AvailableLocalUsers[AuthKeySha1Rule](fromEligibleUsers)
    implicit val authKeySha256Rule: LocalUsersSupport[AuthKeySha256Rule] = AvailableLocalUsers[AuthKeySha256Rule](fromEligibleUsers)
    implicit val authKeySha512Rule: LocalUsersSupport[AuthKeySha512Rule] = AvailableLocalUsers[AuthKeySha512Rule](fromEligibleUsers)
    implicit val authKeyUnixRule: LocalUsersSupport[AuthKeyUnixRule] = AvailableLocalUsers[AuthKeyUnixRule](fromEligibleUsers)
    implicit val proxyAuthRule: LocalUsersSupport[ProxyAuthRule] = AvailableLocalUsers[ProxyAuthRule](fromEligibleUsers)
    implicit val tokenAuthenticationRule: LocalUsersSupport[TokenAuthenticationRule] = AvailableLocalUsers[TokenAuthenticationRule](fromEligibleUsers)
    implicit val usersRule: LocalUsersSupport[UsersRule] = AvailableLocalUsers[UsersRule] {
      _.settings.userIds
        .toList
        .map {
          case RuntimeMultiResolvableVariable.AlreadyResolved(users) =>
            LocalUsers(users.toList.toCovariantSet, unknownUsers = false)
          case RuntimeMultiResolvableVariable.ToBeResolved(_) =>
            LocalUsers(Set.empty, unknownUsers = true)
        }
        .combineAll
    }

    private def fromEligibleUsers[R <: AuthenticationRule](rule: R): LocalUsers = rule.eligibleUsers match {
      case EligibleUsersSupport.Available(users) =>
        LocalUsers(users, unknownUsers = false)
      case EligibleUsersSupport.NotAvailable =>
        LocalUsers(Set.empty, unknownUsers = true)
    }
  }

  implicit val localUsersMonoid: Monoid[LocalUsers] = Monoid.instance(
    emptyValue = LocalUsers.empty,
    cmb = (e1, e2) => {
      val withUnknownUsers = e1.unknownUsers || e2.unknownUsers
      LocalUsers(e1.users ++ e2.users, withUnknownUsers)
    }
  )
}

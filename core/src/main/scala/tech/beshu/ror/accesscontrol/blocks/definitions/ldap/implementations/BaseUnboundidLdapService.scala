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
package tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations

import cats.implicits.catsSyntaxApplicativeError
import com.unboundid.ldap.sdk._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric.Positive
import eu.timepit.refined.types.string.NonEmptyString
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode.NestedGroupsConfig
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserSearchFilterConfig.UserIdAttribute
import tech.beshu.ror.accesscontrol.domain.User
import tech.beshu.ror.utils.LoggerOps.toLoggerOps

import scala.concurrent.duration._

private[implementations] abstract class BaseUnboundidLdapService(connectionPool: UnboundidLdapConnectionPool,
                                                                 userSearchFiler: UserSearchFilterConfig,
                                                                 override val serviceTimeout: FiniteDuration Refined Positive)
  extends LdapUserService with Logging {

  override def ldapUserBy(userId: User.Id): Task[Option[LdapUser]] = {
    userSearchFiler.userIdAttribute match {
      case UserIdAttribute.Cn => createLdapUser(userId)
      case attribute@UserIdAttribute.CustomAttribute(_) => fetchLdapUser(userId, attribute)
    }
  }

  private def createLdapUser(userId: User.Id) = {
    Task.delay {
      Some {
        LdapUser(
          id = userId,
          dn = Dn(NonEmptyString.unsafeFrom(
            s"cn=${Filter.encodeValue(userId.value.value)},${userSearchFiler.searchUserBaseDN.value.value}"
          )),
          confirmed = false
        )
      }
    }
  }

  private def fetchLdapUser(userId: User.Id, uidAttribute: UserIdAttribute.CustomAttribute) = {
    connectionPool
      .process(searchUserLdapRequest(_, userSearchFiler.searchUserBaseDN, uidAttribute, userId), serviceTimeout)
      .flatMap {
        case Right(Nil) =>
          logger.debug("LDAP getting user CN returned no entries")
          Task.now(None)
        case Right(user :: Nil) =>
          Task(Some(LdapUser(userId, Dn(NonEmptyString.unsafeFrom(user.getDN)), confirmed = true)))
        case Right(all@user :: _) =>
          logger.warn(s"LDAP search user - more than one user was returned: ${all.mkString(",")}. Picking first")
          Task(Some(LdapUser(userId, Dn(NonEmptyString.unsafeFrom(user.getDN)), confirmed = true)))
        case Left(errorResult) =>
          logger.error(s"LDAP getting user CN returned error: [code=${errorResult.getResultCode}, cause=${errorResult.getResultString}]")
          Task.raiseError(LdapUnexpectedResult(errorResult.getResultCode, errorResult.getResultString))
      }
      .onError { case ex =>
        Task(logger.errorEx("LDAP getting user operation failed.", ex))
      }
  }

  private def searchUserLdapRequest(listener: AsyncSearchResultListener,
                                    searchUserBaseDN: Dn,
                                    uidAttribute: UserIdAttribute.CustomAttribute,
                                    userId: User.Id): LDAPRequest = {
    new SearchRequest(
      listener,
      searchUserBaseDN.value.value,
      SearchScope.SUB,
      s"${uidAttribute.name.value}=${Filter.encodeValue(userId.value.value)}"
    )
  }
}

final case class LdapUnexpectedResult(code: ResultCode, cause: String)
  extends Throwable(s"LDAP returned code: ${code.getName} [${code.intValue()}], cause: $cause")

final case class UserSearchFilterConfig(searchUserBaseDN: Dn, userIdAttribute: UserIdAttribute)
object UserSearchFilterConfig {

  sealed trait UserIdAttribute
  object UserIdAttribute {
    case object Cn extends UserIdAttribute
    final case class CustomAttribute(name: NonEmptyString) extends UserIdAttribute
  }
}

final case class UserGroupsSearchFilterConfig(mode: UserGroupsSearchMode,
                                              nestedGroupsConfig: Option[NestedGroupsConfig])
object UserGroupsSearchFilterConfig {

  sealed trait UserGroupsSearchMode
  object UserGroupsSearchMode {

    final case class DefaultGroupSearch(searchGroupBaseDN: Dn,
                                        groupSearchFilter: GroupSearchFilter,
                                        groupIdAttribute: GroupIdAttribute,
                                        uniqueMemberAttribute: UniqueMemberAttribute,
                                        groupAttributeIsDN: Boolean)
      extends UserGroupsSearchMode

    final case class GroupsFromUserEntry(searchGroupBaseDN: Dn,
                                         groupSearchFilter: GroupSearchFilter,
                                         groupIdAttribute: GroupIdAttribute,
                                         groupsFromUserAttribute: GroupsFromUserAttribute)
      extends UserGroupsSearchMode

    final case class GroupSearchFilter(value: NonEmptyString)
    object GroupSearchFilter {
      val default: GroupSearchFilter = GroupSearchFilter("(objectClass=*)")
    }
    final case class GroupIdAttribute(value: NonEmptyString)
    object GroupIdAttribute {
      val default: GroupIdAttribute = GroupIdAttribute("cn")
    }
    final case class UniqueMemberAttribute(value: NonEmptyString)
    object UniqueMemberAttribute {
      val default: UniqueMemberAttribute = UniqueMemberAttribute("uniqueMember")
    }
    final case class GroupsFromUserAttribute(value: NonEmptyString)
    object GroupsFromUserAttribute {
      val default: GroupsFromUserAttribute = GroupsFromUserAttribute("memberOf")
    }

    final case class NestedGroupsConfig(nestedLevels: Int Refined Positive,
                                        searchGroupBaseDN: Dn,
                                        groupSearchFilter: GroupSearchFilter,
                                        memberAttribute: UniqueMemberAttribute,
                                        groupIdAttribute: GroupIdAttribute)
  }
}

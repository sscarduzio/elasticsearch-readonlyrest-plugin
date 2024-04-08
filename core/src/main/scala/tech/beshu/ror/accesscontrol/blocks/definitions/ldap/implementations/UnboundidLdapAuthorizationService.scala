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

import cats.implicits._
import com.unboundid.ldap.sdk._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.SearchResultEntryOps._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.{ConnectionError, LdapConnectionConfig}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.domain.LdapGroup
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.{LdapAuthorizationService, LdapService, LdapUser, LdapUsersService}
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.{Group, User}
import tech.beshu.ror.utils.TaskOps._
import tech.beshu.ror.utils.uniquelist.UniqueList

import java.time.Clock
import scala.concurrent.duration.FiniteDuration

class UnboundidLdapAuthorizationService private(override val id: LdapService#Id,
                                                override val ldapUsersService: LdapUsersService,
                                                connectionPool: UnboundidLdapConnectionPool,
                                                groupsSearchFilter: UserGroupsSearchFilterConfig,
                                                override val serviceTimeout: FiniteDuration Refined Positive)
                                               (implicit clock: Clock)
  extends LdapAuthorizationService with Logging {

  private val nestedGroupsService = groupsSearchFilter
    .nestedGroupsConfig
    .map(new UnboundidLdapNestedGroupsService(connectionPool, _, serviceTimeout))

  override def groupsOf(id: User.Id)
                       (implicit requestId: RequestId): Task[UniqueList[Group]] = {
    Task.measure(
      doFetchGroupsOf(id),
      measurement => Task.delay {
        logger.debug(s"[${requestId.show}] LDAP groups fetching took $measurement")
      }
    )
  }

  private def doFetchGroupsOf(id: User.Id)
                             (implicit requestId: RequestId): Task[UniqueList[Group]] = {
    ldapUsersService
      .ldapUserBy(id)
      .flatMap {
        case Some(user) =>
          groupsSearchFilter.mode match {
            case defaultSearchGroupMode: DefaultGroupSearch =>
              groupsFrom(defaultSearchGroupMode, user)
            case groupsFromUserAttribute: GroupsFromUserEntry =>
              groupsFrom(groupsFromUserAttribute, user)
          }
        case None =>
          Task.now(UniqueList.empty)
      }
  }

  private def groupsFrom(mode: DefaultGroupSearch,
                         user: LdapUser)
                        (implicit requestId: RequestId): Task[UniqueList[Group]] = {
    connectionPool
      .process(searchUserGroupsLdapRequest(_, mode, user), serviceTimeout)
      .flatMap {
        case Right(results) =>
          Task {
            results.flatMap(_.toLdapGroup(mode.groupIdAttribute)).toSet
          } flatMap { mainGroups =>
            enrichWithNestedGroupsIfNecessary(mainGroups)
          } map { allGroups =>
            UniqueList.fromIterable(allGroups.map(_.id))
          }
        case Left(errorResult) =>
          logger.error(s"[${requestId.show}] LDAP getting user groups returned error: [code=${errorResult.getResultCode}, cause=${errorResult.getResultString}]")
          Task.raiseError(LdapUnexpectedResult(errorResult.getResultCode, errorResult.getResultString))
      }
      .map(asGroups)
  }

  private def groupsFrom(mode: GroupsFromUserEntry, user: LdapUser)(implicit requestId: RequestId): Task[UniqueList[Group]] = {
    connectionPool
      .process(searchUserGroupsLdapRequest(_, mode, user), serviceTimeout)
      .flatMap {
        case Right(results) =>
          Task {
            results.flatMap(_.toLdapGroups(mode)).toSet
          } flatMap { mainGroups =>
            enrichWithNestedGroupsIfNecessary(mainGroups)
          } map { allGroups =>
            UniqueList.fromIterable(allGroups.map(_.id))
          }
        case Left(errorResult) if errorResult.getResultCode == ResultCode.NO_SUCH_OBJECT && !user.confirmed =>
          logger.error(s"[${requestId.show}] LDAP getting user groups returned error [code=${errorResult.getResultCode}, cause=${errorResult.getResultString}]")
          Task.now(UniqueList.empty[GroupId])
        case Left(errorResult) =>
          logger.error(s"[${requestId.show}] LDAP getting user groups returned error [code=${errorResult.getResultCode}, cause=${errorResult.getResultString}]")
          Task.raiseError(LdapUnexpectedResult(errorResult.getResultCode, errorResult.getResultString))
      }
      .map(asGroups)
      .onError { case ex =>
        Task(logger.error(s"[${requestId.show}] LDAP getting user groups returned error", ex))
      }
  }

  private def searchUserGroupsLdapRequest(listener: AsyncSearchResultListener,
                                          mode: DefaultGroupSearch,
                                          user: LdapUser)
                                         (implicit requestId: RequestId): LDAPRequest = {
    val baseDn = mode.searchGroupBaseDN.value.value
    val scope = SearchScope.SUB
    val searchFilter = searchUserGroupsLdapFilerFrom(mode, user)
    val attribute = mode.groupIdAttribute.value.value
    logger.debug(s"[${requestId.show}] LDAP search [base DN: $baseDn, scope: $scope, search filter: $searchFilter, attributes: $attribute]")
    new SearchRequest(listener, baseDn, scope, searchFilter, attribute)
  }

  private def searchUserGroupsLdapFilerFrom(mode: DefaultGroupSearch,
                                            user: LdapUser) = {
    val userAttributeValue = if (mode.groupAttributeIsDN) user.dn.value else user.id.value
    s"(&${mode.groupSearchFilter.value}(${mode.uniqueMemberAttribute.value}=${Filter.encodeValue(userAttributeValue.value)}))"
  }

  private def searchUserGroupsLdapRequest(listener: AsyncSearchResultListener,
                                          mode: GroupsFromUserEntry,
                                          user: LdapUser)
                                         (implicit requestId: RequestId): LDAPRequest = {
    val baseDn = user.dn.value.value
    val scope = SearchScope.BASE
    val searchFilter = mode.groupSearchFilter.value.value
    val attribute = mode.groupsFromUserAttribute.value.value
    logger.debug(s"[${requestId.show}] LDAP search [base DN: $baseDn, scope: $scope, search filter: $searchFilter, attributes: $attribute]")
    new SearchRequest(listener, baseDn, scope, searchFilter, attribute)
  }

  private def enrichWithNestedGroupsIfNecessary(mainGroups: Set[LdapGroup])(implicit requestId: RequestId) = {
    nestedGroupsService match {
      case Some(service) => service.fetchNestedGroupsOf(mainGroups).map(_ ++ mainGroups)
      case None => Task.delay(mainGroups)
    }
  }

  private def asGroups(groupIds: UniqueList[GroupId]) = {
    UniqueList.fromIterable(groupIds.toList.map(Group.from))
  }
}

object UnboundidLdapAuthorizationService {
  def create(id: LdapService#Id,
             ldapUsersService: LdapUsersService,
             poolProvider: UnboundidLdapConnectionPoolProvider,
             connectionConfig: LdapConnectionConfig,
             userGroupsSearchFilter: UserGroupsSearchFilterConfig)
            (implicit clock: Clock): Task[Either[ConnectionError, UnboundidLdapAuthorizationService]] = {
    UnboundidLdapConnectionPoolProvider
      .connectWithOptionalBindingTest(poolProvider, connectionConfig)
      .map(_.map(connectionPool =>
        new UnboundidLdapAuthorizationService(
          id = id,
          ldapUsersService = ldapUsersService,
          connectionPool = connectionPool,
          groupsSearchFilter = userGroupsSearchFilter,
          serviceTimeout = connectionConfig.requestTimeout)
      ))
  }
}

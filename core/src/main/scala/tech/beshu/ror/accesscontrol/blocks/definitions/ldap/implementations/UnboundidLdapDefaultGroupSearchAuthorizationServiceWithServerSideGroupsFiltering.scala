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
import monix.eval.Task
import org.apache.logging.log4j.scala.Logging
import tech.beshu.ror.RequestId
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.SearchResultEntryOps._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UnboundidLdapConnectionPoolProvider.{ConnectionError, LdapConnectionConfig}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.domain.LdapGroup
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.{LdapAuthorizationServiceWithGroupsFiltering, LdapService, LdapUser, LdapUsersService}
import tech.beshu.ror.accesscontrol.domain.GroupIdLike.GroupId
import tech.beshu.ror.accesscontrol.domain.{Group, GroupIdLike, User}
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration
import tech.beshu.ror.utils.TaskOps._
import tech.beshu.ror.utils.uniquelist.UniqueList

import java.time.Clock

class UnboundidLdapDefaultGroupSearchAuthorizationServiceWithServerSideGroupsFiltering private(override val id: LdapService#Id,
                                                                                               override val ldapUsersService: LdapUsersService,
                                                                                               connectionPool: UnboundidLdapConnectionPool,
                                                                                               groupsSearchFilter: DefaultGroupSearch,
                                                                                               val nestedGroupsConfig: Option[NestedGroupsConfig],
                                                                                               override val serviceTimeout: PositiveFiniteDuration)
                                                                                              (implicit clock: Clock)
  extends LdapAuthorizationServiceWithGroupsFiltering with Logging {

  private val nestedGroupsService = nestedGroupsConfig
    .map(new UnboundidLdapNestedGroupsService(connectionPool, _, serviceTimeout))

  override def groupsOf(id: User.Id, filteringGroupIds: Set[GroupIdLike])
                       (implicit requestId: RequestId): Task[UniqueList[Group]] = {
    Task.measure(
      doFetchGroupsOf(id, filteringGroupIds),
      measurement => Task.delay {
        logger.debug(s"[${requestId.show}] LDAP groups fetching took $measurement")
      }
    )
  }

  private def doFetchGroupsOf(id: User.Id, filteringGroupIds: Set[GroupIdLike])
                             (implicit requestId: RequestId): Task[UniqueList[Group]] = {
    ldapUsersService
      .ldapUserBy(id)
      .flatMap {
        case Some(user) => groupsFrom(groupsSearchFilter, user, filteringGroupIds)
        case None => Task.now(UniqueList.empty)
      }
  }

  private def groupsFrom(mode: DefaultGroupSearch,
                         user: LdapUser,
                         filteringGroupIds: Set[GroupIdLike])
                        (implicit requestId: RequestId): Task[UniqueList[Group]] = {
    connectionPool
      .process(searchUserGroupsLdapRequest(_, mode, user, filteringGroupIds), serviceTimeout)
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

  private def searchUserGroupsLdapRequest(listener: AsyncSearchResultListener,
                                          mode: DefaultGroupSearch,
                                          user: LdapUser,
                                          filteringGroupIds: Set[GroupIdLike])
                                         (implicit requestId: RequestId): LDAPRequest = {
    val baseDn = mode.searchGroupBaseDN.value.value
    val scope = SearchScope.SUB
    val searchFilter = searchUserGroupsLdapFilerFrom(mode, user, filteringGroupIds)
    val attribute = mode.groupIdAttribute.value.value
    logger.debug(s"[${requestId.show}] LDAP search [base DN: $baseDn, scope: $scope, search filter: $searchFilter, attributes: $attribute]")
    new SearchRequest(listener, baseDn, scope, searchFilter, attribute)
  }

  private def searchUserGroupsLdapFilerFrom(mode: DefaultGroupSearch,
                                            user: LdapUser,
                                            filteringGroupIds: Set[GroupIdLike]) = {
    val userAttributeValue = if (mode.groupAttributeIsDN) user.dn.value else user.id.value
    val serverSideGroupsFiltering = filteringGroupIds.toList match {
      case Nil => ""
      case oneGroup :: Nil => s"(${filterPartGroupId(mode.groupIdAttribute, oneGroup)})"
      case manyGroups => manyGroups.map(filterPartGroupId(mode.groupIdAttribute, _)).map(g => s"($g)").sorted.mkString("(|", "", ")")
    }
    s"(&${mode.groupSearchFilter.value}$serverSideGroupsFiltering(${mode.uniqueMemberAttribute.value}=${Filter.encodeValue(userAttributeValue.value)}))"
  }

  private def filterPartGroupId(groupIdAttribute: GroupIdAttribute, groupId: GroupIdLike) = {
    val groupIdString = groupId match {
      case GroupId(value) => value.value
      case GroupIdLike.GroupIdPattern(value) => value.value
    }
    s"${groupIdAttribute.value.value}=$groupIdString"
  }

  private def enrichWithNestedGroupsIfNecessary(mainGroups: Set[LdapGroup])
                                               (implicit requestId: RequestId) = {
    nestedGroupsService match {
      case Some(service) => service.fetchNestedGroupsOf(mainGroups).map(_ ++ mainGroups)
      case None => Task.delay(mainGroups)
    }
  }

  private def asGroups(groupIds: UniqueList[GroupId]) = {
    UniqueList.fromIterable(groupIds.toList.map(Group.from))
  }
}

object UnboundidLdapDefaultGroupSearchAuthorizationServiceWithServerSideGroupsFiltering {
  def create(id: LdapService#Id,
             ldapUsersService: LdapUsersService,
             poolProvider: UnboundidLdapConnectionPoolProvider,
             connectionConfig: LdapConnectionConfig,
             groupsSearchFilter: DefaultGroupSearch,
             nestedGroupsConfig: Option[NestedGroupsConfig])
            (implicit clock: Clock): Task[Either[ConnectionError, LdapAuthorizationServiceWithGroupsFiltering]] = {
    UnboundidLdapConnectionPoolProvider
      .connectWithOptionalBindingTest(poolProvider, connectionConfig)
      .map(_.map(connectionPool =>
        new UnboundidLdapDefaultGroupSearchAuthorizationServiceWithServerSideGroupsFiltering(
          id = id,
          ldapUsersService = ldapUsersService,
          connectionPool = connectionPool,
          groupsSearchFilter = groupsSearchFilter,
          nestedGroupsConfig = nestedGroupsConfig,
          serviceTimeout = connectionConfig.requestTimeout
        )
      ))
  }

}

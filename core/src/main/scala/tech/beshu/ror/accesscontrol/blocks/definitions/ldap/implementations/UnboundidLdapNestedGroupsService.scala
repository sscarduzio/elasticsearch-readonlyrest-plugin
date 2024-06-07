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
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode.{GroupSearchFilter, NestedGroupsConfig, UniqueMemberAttribute}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.domain.LdapGroup
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.utils.DurationOps.PositiveFiniteDuration
import tech.beshu.ror.utils.GraphNodeAncestorsExplorer
import tech.beshu.ror.utils.LoggerOps.toLoggerOps

private[implementations] class UnboundidLdapNestedGroupsService(connectionPool: UnboundidLdapConnectionPool,
                                                                config: NestedGroupsConfig,
                                                                serviceTimeout: PositiveFiniteDuration)
  extends Logging {

  private val ldapGroupsExplorer = new GraphNodeAncestorsExplorer[LdapGroup](
    kinshipLevel = config.nestedLevels,
    doFetchParentNodesOf = { case (ldapGroup, requestId) => doFetchGroupsOf(ldapGroup)(requestId) }
  )

  def fetchNestedGroupsOf(mainGroups: Iterable[LdapGroup])(implicit requestId: RequestId): Task[Set[LdapGroup]] = {
    ldapGroupsExplorer.findAllAncestorsOf(mainGroups)
  }

  private def doFetchGroupsOf(group: LdapGroup)(implicit requestId: RequestId) = {
    connectionPool
      .process(
        requestCreator = searchGroupsOfGroupLdapRequest(_, group),
        timeout = serviceTimeout
      )
      .flatMap {
        case Right(results) =>
          Task.delay {
            results.flatMap(_.toLdapGroup(config.groupIdAttribute, config.groupNameAttribute)).toSet
          }
        case Left(errorResult) =>
          logger.error(s"[${requestId.show}] LDAP getting groups of [${group.group.show}] group returned error: [code=${errorResult.getResultCode}, cause=${errorResult.getResultString}]")
          Task.raiseError(LdapUnexpectedResult(errorResult.getResultCode, errorResult.getResultString))
      }
      .onError { case ex =>
        Task(logger.errorEx(s"[${requestId.show}] LDAP getting groups of [${group.group.show}] group returned error", ex))
      }
  }

  private def searchGroupsOfGroupLdapRequest(listener: AsyncSearchResultListener,
                                             ldapGroup: LdapGroup)
                                            (implicit requestId: RequestId): LDAPRequest = {
    val baseDn = config.searchGroupBaseDN.value.value
    val scope = SearchScope.SUB
    val searchFilter = searchFilterFrom(config.groupSearchFilter, config.memberAttribute, ldapGroup)
    val groupIdAttribute = config.groupIdAttribute.value.value
    val groupNameAttribute = config.groupNameAttribute.value.value
    logger.debug(s"[${requestId.show}] LDAP search [base DN: $baseDn, scope: $scope, search filter: $searchFilter, attributes: $groupIdAttribute;$groupNameAttribute]")
    new SearchRequest(listener, baseDn, scope, searchFilter, groupIdAttribute, groupNameAttribute)
  }

  private def searchFilterFrom(groupSearchFilter: GroupSearchFilter,
                               memberAttribute: UniqueMemberAttribute,
                               group: LdapGroup) = {
    s"(&${groupSearchFilter.value.value}(${Filter.encodeValue(memberAttribute.value.value)}=${Filter.encodeValue(group.dn.value.value)}))"
  }

}


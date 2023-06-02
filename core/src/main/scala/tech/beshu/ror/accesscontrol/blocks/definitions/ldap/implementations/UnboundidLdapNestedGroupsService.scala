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
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.SearchResultEntryOps._
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.UserGroupsSearchFilterConfig.UserGroupsSearchMode.{GroupSearchFilter, NestedGroupsConfig, UniqueMemberAttribute}
import tech.beshu.ror.accesscontrol.blocks.definitions.ldap.implementations.domain.LdapGroup
import tech.beshu.ror.accesscontrol.show.logs._
import tech.beshu.ror.utils.DynamicallyTraversableGraph
import tech.beshu.ror.utils.LoggerOps.toLoggerOps

import scala.concurrent.duration.FiniteDuration

private[implementations] class UnboundidLdapNestedGroupsService(connectionPool: UnboundidLdapConnectionPool,
                                                                config: NestedGroupsConfig,
                                                                serviceTimeout: FiniteDuration Refined Positive)
  extends Logging {

  private val ldapGroupsDynamicallyTraversableGraph = new DynamicallyTraversableGraph[LdapGroup](
    nestedLevels = config.nestedLevels,
    doFetchParentNodesOf = doFetchGroupsOf
  )

  def fetchNestedGroupsOf(mainGroups: Iterable[LdapGroup]): Task[Set[LdapGroup]] = {
    ldapGroupsDynamicallyTraversableGraph.findAllParentsOf(mainGroups)
  }

  private def doFetchGroupsOf(group: LdapGroup) = {
    connectionPool
      .process(
        requestCreator = searchGroupsOfGroupLdapRequest(_, group),
        timeout = serviceTimeout
      )
      .flatMap {
        case Right(results) =>
          Task.delay {
            results.flatMap(_.toLdapGroup(config.groupNameAttribute)).toSet
          }
        case Left(errorResult) =>
          logger.error(s"LDAP getting groups of [${group.name.show}] group returned error: [code=${errorResult.getResultCode}, cause=${errorResult.getResultString}]")
          Task.raiseError(LdapUnexpectedResult(errorResult.getResultCode, errorResult.getResultString))
      }
      .onError { case ex =>
        Task(logger.errorEx(s"LDAP getting groups of [${group.name.show}] group returned error", ex))
      }
  }

  private def searchGroupsOfGroupLdapRequest(listener: AsyncSearchResultListener,
                                             ldapGroup: LdapGroup): LDAPRequest = {
    val baseDn = config.searchGroupBaseDN.value.value
    val scope = SearchScope.SUB
    val searchFilter = searchFilterFrom(config.groupSearchFilter, config.memberAttribute, ldapGroup)
    val attribute = config.groupNameAttribute.value.value
    logger.debug(s"LDAP search [base DN: $baseDn, scope: $scope, search filter: $searchFilter, attributes: $attribute]")
    new SearchRequest(listener, baseDn, scope, searchFilter, attribute)
  }

  private def searchFilterFrom(groupSearchFilter: GroupSearchFilter,
                               memberAttribute: UniqueMemberAttribute,
                               group: LdapGroup) = {
    s"(&${groupSearchFilter.value}(${memberAttribute.value}=${group.dn.value}))"
  }

}


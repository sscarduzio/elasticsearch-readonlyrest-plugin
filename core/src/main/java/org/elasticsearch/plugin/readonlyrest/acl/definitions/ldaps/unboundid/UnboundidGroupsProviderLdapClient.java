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
package org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.unboundid;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.GroupsProviderLdapClient;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.LdapGroup;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.LdapUser;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class UnboundidGroupsProviderLdapClient extends UnboundidAuthenticationLdapClient implements GroupsProviderLdapClient {

  private final Logger logger;

  private final UserGroupsSearchFilterConfig userGroupsSearchFilterConfig;

  public UnboundidGroupsProviderLdapClient(ConnectionConfig connectionConfig,
                                           UserSearchFilterConfig userSearchFilterConfig,
                                           UserGroupsSearchFilterConfig userGroupsSearchFilterConfig,
                                           Optional<SearchingUserConfig> searchingUserConfig,
                                           ESContext context) {
    super(new UnboundidConnection(connectionConfig, searchingUserConfig),
        connectionConfig.getRequestTimeout(), userSearchFilterConfig, context);
    this.userGroupsSearchFilterConfig = userGroupsSearchFilterConfig;
    this.logger = context.logger(getClass());
  }

  public UnboundidGroupsProviderLdapClient(UnboundidConnection connection,
                                           Duration requestTimeout,
                                           UserSearchFilterConfig userSearchFilterConfig,
                                           UserGroupsSearchFilterConfig userGroupsSearchFilterConfig,
                                           ESContext context) {
    super(connection, requestTimeout, userSearchFilterConfig, context);
    this.userGroupsSearchFilterConfig = userGroupsSearchFilterConfig;
    this.logger = context.logger(getClass());
  }

  @Override
  public CompletableFuture<Set<LdapGroup>> userGroups(LdapUser user) {
    try {
      CompletableFuture<List<SearchResultEntry>> searchGroups = new CompletableFuture<>();
      connection.getConnectionPool().processRequestsAsync(
          Lists.newArrayList(
              new SearchRequest(
                  new UnboundidSearchResultListener(searchGroups),
                  userGroupsSearchFilterConfig.getSearchGroupBaseDN(),
                  SearchScope.SUB,
                  String.format(
                      "(&(cn=*)(%s=%s))", userGroupsSearchFilterConfig.getUniqueMemberAttribute(),
                      Filter.encodeValue(user.getDN())
                  )
              )),
          requestTimeout.toMillis()
      );
      return searchGroups
          .thenApply(groupSearchResult -> groupSearchResult.stream()
              .map(it -> Optional.ofNullable(it.getAttributeValue("cn")))
              .filter(Optional::isPresent)
              .map(Optional::get)
              .map(LdapGroup::new)
              .collect(Collectors.toSet()))
          .exceptionally(t -> {
            if (t instanceof LdapSearchError) {
              LdapSearchError error = (LdapSearchError) t;
              logger.debug(String.format("LDAP getting user groups returned error [%s]", error.getResultString()));
            }
            return Sets.newHashSet();
          });
    } catch (LDAPException e) {
      logger.error("LDAP getting user groups operation failed", e);
      return CompletableFuture.completedFuture(Sets.newHashSet());
    }
  }
}

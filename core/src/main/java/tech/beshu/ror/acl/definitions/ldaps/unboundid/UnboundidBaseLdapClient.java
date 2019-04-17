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
package tech.beshu.ror.acl.definitions.ldaps.unboundid;

import com.google.common.collect.Lists;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import tech.beshu.ror.acl.definitions.ldaps.BaseLdapClient;
import tech.beshu.ror.acl.definitions.ldaps.LdapClientException;
import tech.beshu.ror.acl.definitions.ldaps.LdapUser;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public abstract class UnboundidBaseLdapClient implements BaseLdapClient {

  protected final Duration requestTimeout;
  protected final UnboundidConnection connection;
  private final LoggerShim logger;
  private final UserSearchFilterConfig userSearchFilterConfig;

  UnboundidBaseLdapClient(UnboundidConnection connection,
                          Duration requestTimeout,
                          UserSearchFilterConfig userSearchFilterConfig,
                          ESContext context) {
    this.logger = context.logger(getClass());
    this.connection = connection;
    this.requestTimeout = requestTimeout;
    this.userSearchFilterConfig = userSearchFilterConfig;
  }

  @Override
  public CompletableFuture<Optional<LdapUser>> userById(String userId) {
    try {
      CompletableFuture<List<SearchResultEntry>> searchUser = new CompletableFuture<>();
      String filterString = String.format("(%s=%s)", userSearchFilterConfig.getUidAttribute(), Filter.encodeValue(userId));

      logger.debug("Sending LDAP request by ID: {baseDN: " +
          userSearchFilterConfig.getSearchUserBaseDN() +
          ", scope: "+SearchScope.SUB + " filter: '" + filterString + "'}");

      connection.getConnectionPool().processRequestsAsync(
        Lists.newArrayList(
          new SearchRequest(
            new UnboundidSearchResultListener(searchUser),
            userSearchFilterConfig.getSearchUserBaseDN(),
            SearchScope.SUB,
            String.format("(%s=%s)", userSearchFilterConfig.getUidAttribute(), Filter.encodeValue(userId))
          )),
        requestTimeout.toMillis()
      );
      return searchUser
        .thenApply(userSearchResult -> {
          if (userSearchResult != null && userSearchResult.size() > 0) {
            return Optional.of(new LdapUser(userId, userSearchResult.get(0).getDN()));
          }
          else {
            logger.debug("LDAP getting user CN returned no entries");
            return Optional.<LdapUser>empty();
          }
        })
        .exceptionally(t -> {
          if (t.getCause() instanceof LdapSearchError) {
            LdapSearchError error = (LdapSearchError) t.getCause();
            logger.debug(String.format("LDAP getting user CN returned error [%s]", error.getResultString()));
            return Optional.empty();
          }
          throw new LdapClientException.SearchException(t);
        });
    } catch (LDAPException e) {
      logger.error("LDAP getting user operation failed.  " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
      return CompletableFuture.completedFuture(Optional.empty());
    }
  }

}

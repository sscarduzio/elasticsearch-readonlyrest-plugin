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

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.unboundid.ldap.sdk.Attribute;
import com.unboundid.ldap.sdk.DN;
import com.unboundid.ldap.sdk.Filter;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchScope;
import tech.beshu.ror.acl.definitions.ldaps.GroupsProviderLdapClient;
import tech.beshu.ror.acl.definitions.ldaps.LdapGroup;
import tech.beshu.ror.acl.definitions.ldaps.LdapUser;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class UnboundidGroupsProviderLdapClient extends UnboundidAuthenticationLdapClient implements GroupsProviderLdapClient {

  private final LoggerShim logger;

  private final UserGroupsSearchFilterConfig userGroupsSearchFilterConfig;

  public UnboundidGroupsProviderLdapClient(ConnectionConfig connectionConfig,
      UserSearchFilterConfig userSearchFilterConfig,
      UserGroupsSearchFilterConfig userGroupsSearchFilterConfig,
      Optional<SearchingUserConfig> searchingUserConfig,
      ESContext context) {
    super(new UnboundidConnection(connectionConfig, searchingUserConfig),
        connectionConfig.getRequestTimeout(), userSearchFilterConfig, context
    );
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
      return userGroupsSearchFilterConfig.isGroupsFromUser() ? getGroupsFromUser(user) : getGroups(user);
    } catch (LDAPException e) {
      logger.error("LDAP getting user groups operation failed", e);
      return CompletableFuture.completedFuture(Sets.newHashSet());
    }
  }

  private CompletableFuture<Set<LdapGroup>> getGroups(LdapUser user) throws LDAPException {
    // Compose the search string
    String searchString = String.format(
        "(&%s(%s=%s))",
        userGroupsSearchFilterConfig.getGroupSearchFilter(),
        userGroupsSearchFilterConfig.getUniqueMemberAttribute(),
        Filter.encodeValue(user.getDN())
    );
    logger.debug("LDAP search string: " + searchString + "  |  groupNameAttr: " + userGroupsSearchFilterConfig.getGroupNameAttribute());

    // Request formulation
    CompletableFuture<List<SearchResultEntry>> searchGroups = new CompletableFuture<>();
    connection.getConnectionPool().processRequestsAsync(
        Lists.newArrayList(
            new SearchRequest(
                new UnboundidSearchResultListener(searchGroups),
                userGroupsSearchFilterConfig.getSearchGroupBaseDN(),
                SearchScope.SUB,
                searchString,
                userGroupsSearchFilterConfig.getGroupNameAttribute()
            )),
        requestTimeout.toMillis()
    );

    // Response adaptation
    return searchGroups
        .thenApply(groupSearchResult -> groupSearchResult.stream()
                                                         .map(it -> Optional.ofNullable(
                                                             it.getAttributeValue(userGroupsSearchFilterConfig.getGroupNameAttribute())))
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
  }

  private CompletableFuture<Set<LdapGroup>> getGroupsFromUser(LdapUser user) throws LDAPException {
    logger.debug("LDAP search string: " + user.getDN() + "  |  groupsFromUserAttribute: " + userGroupsSearchFilterConfig.getGroupsFromUserAttribute());

    // Request formulation
    CompletableFuture<List<SearchResultEntry>> searchGroups = new CompletableFuture<>();
    connection.getConnectionPool().processRequestsAsync(
        Lists.newArrayList(
            new SearchRequest(
                new UnboundidSearchResultListener(searchGroups),
                user.getDN(),
                SearchScope.BASE,
                "(objectClass=*)",
                userGroupsSearchFilterConfig.getGroupsFromUserAttribute()
            )),
        requestTimeout.toMillis()
    );

    // Response adaptation
    return searchGroups
        .thenApply(sg -> {
          logger.debug("getGroupsFromUser got " + sg.size() + " responses. ");
          Set<String> sGroupsStr = sg
              .stream()
              .map(x -> {
                Set<String> tmp = x.getAttributes().
                    stream().map(y -> y.getName() + "=" + y.getValue()).collect(Collectors.toSet());

                String formattedAttrs = Joiner.on(", ").join(tmp);
                return "sg=" + x.getDN() + ", attrs={ " + formattedAttrs + " }";
              })
              .collect(Collectors.toSet());

          logger.debug("getGroupsFromUser responses: \n" + Joiner.on("\n").join(sGroupsStr));
          logger.debug("Will now get attribute values of " + userGroupsSearchFilterConfig.getGroupsFromUserAttribute());

          return sg;
        })
        .thenApply(groupSearchResult -> groupSearchResult
            .stream()
            .map(it -> Stream.of(
                it.getAttributeValues(userGroupsSearchFilterConfig.getGroupsFromUserAttribute())))
            .reduce(Stream.empty(), Stream::concat)
            .map(this::getGroupNameFromDN)
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
  }

  private Optional<String> getGroupNameFromDN(String stringDn) {
    try {
      DN dn = new DN(stringDn);
      if (dn.isDescendantOf(userGroupsSearchFilterConfig.getSearchGroupBaseDN(), false)) {
        return Stream.of(dn.getRDN().getAttributes())
                     .filter(attribute -> userGroupsSearchFilterConfig.getGroupNameAttribute().equals(attribute.getBaseName()))
                     .map(Attribute::getValue)
                     .findFirst();
      }
    } catch (Exception e) {
      /* ignore */
    }
    return Optional.empty();
  }

}

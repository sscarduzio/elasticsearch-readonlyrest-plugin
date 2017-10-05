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
package tech.beshu.ror.acl.definitions.groupsproviders;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.jayway.jsonpath.JsonPath;
import tech.beshu.ror.commons.shims.ESContext;
import tech.beshu.ror.commons.shims.LoggerShim;
import tech.beshu.ror.acl.domain.LoggedUser;
import tech.beshu.ror.commons.shims.HttpClient;
import tech.beshu.ror.commons.shims.RRHttpRequest;
import tech.beshu.ror.commons.shims.RRHttpResponse;
import tech.beshu.ror.settings.definitions.UserGroupsProviderSettings;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class GroupsProviderServiceHttpClient implements GroupsProviderServiceClient {

  private final LoggerShim logger;
  private final HttpClient client;
  private final String name;
  private final URI endpoint;
  private final String authTokenName;
  private final UserGroupsProviderSettings.TokenPassingMethod passingMethod;
  private final String responseGroupsJsonPath;

  public GroupsProviderServiceHttpClient(String name,
                                         HttpClient client,
                                         URI endpoint,
                                         String authTokenName,
                                         UserGroupsProviderSettings.TokenPassingMethod passingMethod,
                                         String responseGroupsJsonPath,
                                         ESContext context) {
    this.logger = context.logger(getClass());
    this.name = name;
    this.endpoint = endpoint;
    this.authTokenName = authTokenName;
    this.passingMethod = passingMethod;
    this.responseGroupsJsonPath = responseGroupsJsonPath;
    this.client = client;
  }

  @Override
  public CompletableFuture<Set<String>> fetchGroupsFor(LoggedUser user) {
    return client.send(RRHttpRequest.get(endpoint, createParams(user), createHeaders(user)))
      .thenApply(groupsFromResponse());
  }

  private Map<String, String> createParams(LoggedUser user) {
    return passingMethod == UserGroupsProviderSettings.TokenPassingMethod.QUERY
      ? ImmutableMap.of(authTokenName, user.getId())
      : ImmutableMap.of();
  }

  private Map<String, String> createHeaders(LoggedUser user) {
    return passingMethod == UserGroupsProviderSettings.TokenPassingMethod.HEADER
      ? ImmutableMap.of(authTokenName, user.getId())
      : ImmutableMap.of();
  }

  private Function<RRHttpResponse, Set<String>> groupsFromResponse() {
    return response -> {
      if (response.getStatusCode() == 200) {
        try {
          List<String> groups = JsonPath.read(response.getContent().get(), responseGroupsJsonPath);
          logger.debug("Groups returned by groups provider '" + name + "': " + Joiner.on(",").join(groups));
          return Sets.newHashSet(groups);
        } catch (Exception e) {
          logger.error("Group based authorization response exception", e);
          return Sets.newHashSet();
        }
      }

      return Sets.newHashSet();
    };
  }
}

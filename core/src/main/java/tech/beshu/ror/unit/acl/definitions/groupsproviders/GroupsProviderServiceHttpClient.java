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
package tech.beshu.ror.unit.acl.definitions.groupsproviders;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.jayway.jsonpath.JsonPath;
import tech.beshu.ror.commons.domain.__old_LoggedUser;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.httpclient.HttpClient;
import tech.beshu.ror.httpclient.HttpMethod;
import tech.beshu.ror.httpclient.RRHttpRequest;
import tech.beshu.ror.httpclient.RRHttpResponse;
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
  private final ImmutableMap<String, String> defaultHeaders;
  private final ImmutableMap<String, String> defaultQueryParameters;
  private Function<__old_LoggedUser,RRHttpRequest> requestBuilder;

  public GroupsProviderServiceHttpClient(String name,
                                         HttpClient client,
                                         URI endpoint,
                                         String authTokenName,
                                         HttpMethod method,
                                         UserGroupsProviderSettings.TokenPassingMethod passingMethod,
                                         String responseGroupsJsonPath,
                                         ImmutableMap<String, String> defaultHeaders, ImmutableMap<String,
      String> defaultQueryParameters, ESContext context) {
    this.logger = context.logger(getClass());
    this.name = name;
    this.endpoint = endpoint;
    this.authTokenName = authTokenName;
    this.passingMethod = passingMethod;
    this.responseGroupsJsonPath = responseGroupsJsonPath;
    this.client = client;
    this.defaultHeaders = defaultHeaders;
    this.defaultQueryParameters = defaultQueryParameters;
    this.requestBuilder = method == HttpMethod.POST ? (t ->
        RRHttpRequest.post(this.endpoint, createParams(t), createHeaders(t))) :
        (t -> RRHttpRequest.get(this.endpoint, createParams(t), createHeaders(t)));
  }

  @Override
  public CompletableFuture<Set<String>> fetchGroupsFor(__old_LoggedUser user) {

    return client.send(requestBuilder.apply(user))
        .thenApply(groupsFromResponse());
  }

  private Map<String, String> createParams(__old_LoggedUser user) {
    return passingMethod == UserGroupsProviderSettings.TokenPassingMethod.QUERY
        ? new ImmutableMap.Builder<String, String>().putAll(defaultQueryParameters.entrySet()).put(authTokenName, user.getId()).build()
        : ImmutableMap.copyOf(defaultQueryParameters);
  }

  private Map<String, String> createHeaders(__old_LoggedUser user) {
    return passingMethod == UserGroupsProviderSettings.TokenPassingMethod.HEADER
        ? new ImmutableMap.Builder<String, String>().putAll(defaultHeaders.entrySet()).put(authTokenName, user.getId()).build()
        : ImmutableMap.copyOf(defaultHeaders);
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

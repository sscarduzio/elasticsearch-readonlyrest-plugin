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
package org.elasticsearch.plugin.readonlyrest.acl.definitions.groupsproviders;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.jayway.jsonpath.JsonPath;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.plugin.readonlyrest.acl.domain.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.GroupsProviderAuthorizationAsyncRule;
import org.elasticsearch.plugin.readonlyrest.utils.CompletableFutureResponseListener;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class GroupsProviderServiceHttpClient implements GroupsProviderServiceClient {

  public enum TokenPassingMethod {
    QUERY, HEADER
  }

  private final Logger logger = Loggers.getLogger(GroupsProviderAuthorizationAsyncRule.class);

  private final RestClient client;
  private final String name;
  private final URI endpoint;
  private final String authTokenName;
  private final TokenPassingMethod passingMethod;
  private final String responseGroupsJsonPath;

  public GroupsProviderServiceHttpClient(String name,
                                         URI endpoint,
                                         String authTokenName,
                                         TokenPassingMethod passingMethod,
                                         String responseGroupsJsonPath) {
    this.name = name;
    this.endpoint = endpoint;
    this.authTokenName = authTokenName;
    this.passingMethod = passingMethod;
    this.responseGroupsJsonPath = responseGroupsJsonPath;
    this.client = RestClient.builder(
        new HttpHost(
            endpoint.getHost(),
            endpoint.getPort()
        )
    ).build();
  }

  @Override
  public CompletableFuture<Set<String>> fetchGroupsFor(LoggedUser user) {
    final CompletableFuture<Set<String>> promise = new CompletableFuture<>();
    client.performRequestAsync(
        "GET",
        endpoint.getPath(),
        createParams(user),
        new CompletableFutureResponseListener<>(promise, groupsFromResponse()),
        createHeaders(user).toArray(new Header[0])
    );
    return promise;
  }

  private Map<String, String> createParams(LoggedUser user) {
    Map<String, String> params = new HashMap<>();
    if(passingMethod == TokenPassingMethod.QUERY) {
      params.put(authTokenName, user.getId());
    }
    return params;
  }

  private List<Header> createHeaders(LoggedUser user) {
    return passingMethod == TokenPassingMethod.HEADER
        ? Lists.newArrayList(new BasicHeader(authTokenName, user.getId()))
        : Lists.newArrayList();
  }

  private Function<Response, Set<String>> groupsFromResponse() {
    return response -> {
      if (response.getStatusLine().getStatusCode() == 200) {
        try {
          List<String> groups = JsonPath.read(response.getEntity().getContent(), responseGroupsJsonPath);
          logger.debug("Groups returned by groups provider '" + name + "': "
              + Joiner.on(",").join(groups));

          return Sets.newHashSet(groups);
        } catch (IOException e) {
          logger.error("Group based authorization response exception", e);
          return Sets.newHashSet();
        }
      }

      return Sets.newHashSet();
    };
  }
}

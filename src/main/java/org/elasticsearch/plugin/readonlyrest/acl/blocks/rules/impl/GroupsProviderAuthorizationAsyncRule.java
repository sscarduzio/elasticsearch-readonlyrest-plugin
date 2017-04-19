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
package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.jayway.jsonpath.JsonPath;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseListener;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncAuthorization;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.utils.CompletableFutureResponseListener;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper.requiredAttributeArrayValue;
import static org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper.requiredAttributeValue;

public class GroupsProviderAuthorizationAsyncRule extends AsyncAuthorization {

  private static final Logger logger = Loggers.getLogger(GroupsProviderAuthorizationAsyncRule.class);

  private static final String RULE_NAME = "groups_provider_authorization";
  private static final String ATTRIBUTE_USER_GROUPS_PROVIDER = "user_groups_provider";
  private static final String ATTRIBUTE_GROUPS = "groups";

  private final ProviderGroupsAuthDefinition providerGroupsAuthDefinition;
  private final RestClient client;

  private GroupsProviderAuthorizationAsyncRule(ProviderGroupsAuthDefinition definition) {
    this.providerGroupsAuthDefinition = definition;
    URI groupBasedAuthEndpoint = providerGroupsAuthDefinition.config.getEndpoint();
    this.client = RestClient.builder(
        new HttpHost(
            groupBasedAuthEndpoint.getHost(),
            groupBasedAuthEndpoint.getPort()
        )
    ).build();
  }

  public static Optional<GroupsProviderAuthorizationAsyncRule> fromSettings(Settings s,
                                                                            List<UserGroupProviderConfig> groupProviderConfigs)
      throws ConfigMalformedException {

    Map<String, Settings> groupBaseAuthElements = s.getGroups(RULE_NAME);
    if (groupBaseAuthElements.isEmpty()) return Optional.empty();
    if (groupBaseAuthElements.size() != 1) {
      throw new ConfigMalformedException("Malformed rule" + RULE_NAME);
    }
    Settings groupBaseAuthSettings = Lists.newArrayList(groupBaseAuthElements.values()).get(0);

    Map<String, UserGroupProviderConfig> userGroupProviderConfigByName = groupProviderConfigs.stream()
        .collect(Collectors.toMap(UserGroupProviderConfig::getName, Function.identity()));

    String name = requiredAttributeValue(ATTRIBUTE_USER_GROUPS_PROVIDER, groupBaseAuthSettings);
    if (!userGroupProviderConfigByName.containsKey(name)) {
      throw new ConfigMalformedException("User groups provider with name [" + name + "] wasn't defined.");
    }
    List<String> groups = requiredAttributeArrayValue(ATTRIBUTE_GROUPS, groupBaseAuthSettings);

    return Optional.of(new GroupsProviderAuthorizationAsyncRule(
        new ProviderGroupsAuthDefinition(userGroupProviderConfigByName.get(name), groups)
    ));
  }

  @Override
  protected CompletableFuture<Boolean> authorize(LoggedUser user) {
    final CompletableFuture<Boolean> promise = new CompletableFuture<>();
    client.performRequestAsync(
        "GET",
        providerGroupsAuthDefinition.config.getEndpoint().getPath(),
        createParams(user),
        new CompletableFutureResponseListener<>(promise, isAuthorized()),
        createHeaders(user).toArray(new Header[0])
    );
    return promise;
  }

  private Map<String, String> createParams(LoggedUser user) {
    Map<String, String> params = new HashMap<>();
    if(providerGroupsAuthDefinition.config.getPassingMethod() == UserGroupProviderConfig.TokenPassingMethod.QUERY) {
      params.put(providerGroupsAuthDefinition.config.getAuthTokenName(), user.getId());
    }
    return params;
  }

  private List<Header> createHeaders(LoggedUser user) {
    return providerGroupsAuthDefinition.config.getPassingMethod() == UserGroupProviderConfig.TokenPassingMethod.HEADER
        ? Lists.newArrayList(new BasicHeader(providerGroupsAuthDefinition.config.getAuthTokenName(), user.getId()))
        : Lists.newArrayList();
  }

  private Function<Response, Boolean> isAuthorized() {
    return response -> {
      if (response.getStatusLine().getStatusCode() == 200) {
        try {
          List<String> groups = JsonPath.read(
              response.getEntity().getContent(),
              providerGroupsAuthDefinition.config.getResponseGroupsJsonPath()
          );
          logger.debug("Groups returned by groups provider '" + providerGroupsAuthDefinition.config.getName() + "': "
              + Joiner.on(",").join(groups));

          Sets.SetView<String> intersection = Sets.intersection(providerGroupsAuthDefinition.groups, Sets.newHashSet(groups));
          return !intersection.isEmpty();
        } catch (IOException e) {
          logger.error("Group based authorization response exception", e);
          return false;
        }
      }

      return false;
    };
  }

  @Override
  public String getKey() {
    return RULE_NAME;
  }

  private static class ProviderGroupsAuthDefinition {
    private final UserGroupProviderConfig config;
    private final ImmutableSet<String> groups;

    ProviderGroupsAuthDefinition(UserGroupProviderConfig config, List<String> groups) {
      this.config = config;
      this.groups = ImmutableSet.copyOf(groups);
    }
  }

}

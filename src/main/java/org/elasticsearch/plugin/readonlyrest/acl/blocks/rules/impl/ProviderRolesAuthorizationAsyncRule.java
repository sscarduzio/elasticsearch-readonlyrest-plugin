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

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper.requiredAttributeArrayValue;
import static org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper.requiredAttributeValue;

public class ProviderRolesAuthorizationAsyncRule extends AsyncAuthorization {

  private static final Logger logger = Loggers.getLogger(ProviderRolesAuthorizationAsyncRule.class);

  private static final String RULE_NAME = "provider_roles_authorization";
  private static final String ATTRIBUTE_USER_ROLE_PROVIDER = "user_role_provider";
  private static final String ATTRIBUTE_ROLES = "roles";

  private final ProviderRolesAuthDefinition providerRolesAuthDefinition;
  private final RestClient client;

  private ProviderRolesAuthorizationAsyncRule(ProviderRolesAuthDefinition definition) {
    this.providerRolesAuthDefinition = definition;
    URI roleBasedAuthEndpoint = providerRolesAuthDefinition.config.getEndpoint();
    this.client = RestClient.builder(
        new HttpHost(
            roleBasedAuthEndpoint.getHost(),
            roleBasedAuthEndpoint.getPort()
        )
    ).build();
  }

  public static Optional<ProviderRolesAuthorizationAsyncRule> fromSettings(Settings s,
                                                                           List<UserRoleProviderConfig> roleProviderConfigs)
      throws ConfigMalformedException {

    Map<String, Settings> roleBaseAuthElements = s.getGroups(RULE_NAME);
    if (roleBaseAuthElements.isEmpty()) return Optional.empty();
    if (roleBaseAuthElements.size() != 1) {
      throw new ConfigMalformedException("Malformed rule" + RULE_NAME);
    }
    Settings roleBaseAuthSettings = Lists.newArrayList(roleBaseAuthElements.values()).get(0);

    Map<String, UserRoleProviderConfig> userRoleProviderConfigByName = roleProviderConfigs.stream()
        .collect(Collectors.toMap(UserRoleProviderConfig::getName, Function.identity()));

    String name = requiredAttributeValue(ATTRIBUTE_USER_ROLE_PROVIDER, roleBaseAuthSettings);
    if (!userRoleProviderConfigByName.containsKey(name)) {
      throw new ConfigMalformedException("User role provider with name [" + name + "] wasn't defined.");
    }
    List<String> roles = requiredAttributeArrayValue(ATTRIBUTE_ROLES, roleBaseAuthSettings);

    return Optional.of(new ProviderRolesAuthorizationAsyncRule(new ProviderRolesAuthDefinition(userRoleProviderConfigByName.get(name), roles)));
  }

  @Override
  protected CompletableFuture<Boolean> authorize(LoggedUser user, Set<String> roles) {
    final CompletableFuture<Boolean> promise = new CompletableFuture<>();
    client.performRequestAsync(
        "GET",
        providerRolesAuthDefinition.config.getEndpoint().getPath(),
        createParams(user),
        new RoleBasedAuthResponseListener<>(promise, isAuthorized(roles)),
        createHeaders(user).toArray(new Header[0])
    );
    return promise;
  }

  @Override
  protected Set<String> getRoles() {
    return providerRolesAuthDefinition.roles;
  }

  private Map<String, String> createParams(LoggedUser user) {
    Map<String, String> params = new HashMap<>();
    if(providerRolesAuthDefinition.config.getPassingMethod() == UserRoleProviderConfig.TokenPassingMethod.QUERY) {
      params.put(providerRolesAuthDefinition.config.getAuthTokenName(), user.getId());
    }
    return params;
  }

  private List<Header> createHeaders(LoggedUser user) {
    return providerRolesAuthDefinition.config.getPassingMethod() == UserRoleProviderConfig.TokenPassingMethod.HEADER
        ? Lists.newArrayList(new BasicHeader(providerRolesAuthDefinition.config.getAuthTokenName(), user.getId()))
        : Lists.newArrayList();
  }

  private Function<Response, Boolean> isAuthorized(Set<String> ruleRoles) {
    return response -> {
      if (response.getStatusLine().getStatusCode() == 200) {
        try {
          List<String> roles = JsonPath.read(
              response.getEntity().getContent(),
              providerRolesAuthDefinition.config.getResponseRolesJsonPath()
          );
          logger.debug("Roles returned by role provider '" + providerRolesAuthDefinition.config.getName() + "': "
              + Joiner.on(",").join(roles));

          Sets.SetView<String> intersection = Sets.intersection(ruleRoles, Sets.newHashSet(roles));
          return !intersection.isEmpty();
        } catch (IOException e) {
          logger.error("Role based authorization response exception", e);
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

  private static class ProviderRolesAuthDefinition {
    private final UserRoleProviderConfig config;
    private final ImmutableSet<String> roles;

    ProviderRolesAuthDefinition(UserRoleProviderConfig config, List<String> roles) {
      this.config = config;
      this.roles = ImmutableSet.copyOf(roles);
    }
  }

  private static class RoleBasedAuthResponseListener<T> implements ResponseListener {

    private final CompletableFuture<T> promise;
    private final Function<Response, T> converter;

    RoleBasedAuthResponseListener(CompletableFuture<T> promise,
                                  Function<Response, T> converter) {
      this.promise = promise;
      this.converter = converter;
    }

    @Override
    public void onSuccess(Response response) {
      promise.complete(converter.apply(response));
    }

    @Override
    public void onFailure(Exception exception) {
      promise.completeExceptionally(exception);
    }
  }
}

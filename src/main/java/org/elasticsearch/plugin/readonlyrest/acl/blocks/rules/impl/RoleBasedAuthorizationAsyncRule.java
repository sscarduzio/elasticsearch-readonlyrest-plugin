package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.jayway.jsonpath.JsonPath;
import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseListener;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncAuthorization;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper.requiredAttributeArrayValue;
import static org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper.requiredAttributeValue;

public class RoleBasedAuthorizationAsyncRule extends AsyncAuthorization {

  private static final String RULE_NAME = "role_based_authorization";
  private static final String ATTRIBUTE_USER_ROLE_PROVIDER = "user_role_provider";
  private static final String ATTRIBUTE_ROLES = "roles";

  private final RoleBaseAuthDefinition roleBaseAuthDefinition;
  private final RestClient client;

  private RoleBasedAuthorizationAsyncRule(RoleBaseAuthDefinition definition) {
    this.roleBaseAuthDefinition = definition;
    URI roleBasedAuthEndpoint = roleBaseAuthDefinition.config.getEndpoint();
    this.client = RestClient.builder(
        new HttpHost(
            roleBasedAuthEndpoint.getHost(),
            roleBasedAuthEndpoint.getPort()
        )
    ).build();
  }

  public static Optional<RoleBasedAuthorizationAsyncRule> fromSettings(Settings s,
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

    return Optional.of(new RoleBasedAuthorizationAsyncRule(new RoleBaseAuthDefinition(userRoleProviderConfigByName.get(name), roles)));
  }

  @Override
  public CompletableFuture<RuleExitResult> match(RequestContext rc) {
    Optional<LoggedUser> optLoggedInUser = rc.getLoggedInUser();
    if(optLoggedInUser.isPresent()) {
      LoggedUser loggedUser = optLoggedInUser.get();
      return authorize(loggedUser, roleBaseAuthDefinition.roles).thenApply(result -> result ? MATCH : NO_MATCH);
    } else {
      // todo: log
      return CompletableFuture.completedFuture(NO_MATCH);
    }
  }

  @Override
  public CompletableFuture<Boolean> authorize(LoggedUser user, Set<String> roles) {
    final CompletableFuture<Boolean> promise = new CompletableFuture<>();
    client.performRequestAsync(
        "GET",
        roleBaseAuthDefinition.config.getEndpoint().getPath(),
        createParams(user),
        new RoleBasedAuthResponseListener<>(promise, isAuthorized(roles)),
        createHeaders(user).toArray(new Header[0])
    );
    return promise;
  }

  private Map<String, String> createParams(LoggedUser user) {
    Map<String, String> params = new HashMap<>();
    if(roleBaseAuthDefinition.config.getPassingMethod() == UserRoleProviderConfig.TokenPassingMethod.QUERY) {
      params.put(roleBaseAuthDefinition.config.getAuthTokenName(), user.getId());
    }
    return params;
  }

  private List<Header> createHeaders(LoggedUser user) {
    return roleBaseAuthDefinition.config.getPassingMethod() == UserRoleProviderConfig.TokenPassingMethod.HEADER
        ? Lists.newArrayList(new BasicHeader(roleBaseAuthDefinition.config.getAuthTokenName(), user.getId()))
        : Lists.newArrayList();
  }

  private Function<Response, Boolean> isAuthorized(Set<String> ruleRoles) {
    return response -> {
      if (response.getStatusLine().getStatusCode() == 200) {
        try {
          List<String> roles = JsonPath.read(
              response.getEntity().getContent(),
              roleBaseAuthDefinition.config.getResponseRolesJsonPath()
          );
          Sets.SetView<String> intersection = Sets.intersection(ruleRoles, Sets.newHashSet(roles));
          return !intersection.isEmpty();
        } catch (IOException e) {
          // todo: log
          e.printStackTrace();
        }
      }

      return false;
    };
  }

  @Override
  public String getKey() {
    return RULE_NAME;
  }

  private static class RoleBaseAuthDefinition {
    private final UserRoleProviderConfig config;
    private final ImmutableSet<String> roles;

    RoleBaseAuthDefinition(UserRoleProviderConfig config, List<String> roles) {
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

package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.BasicAsyncAuthentication;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.utils.BasicAuthUtils;
import org.elasticsearch.plugin.readonlyrest.utils.CompletableFutureResponseListener;
import org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ExternalAuthenticationAsyncRule extends BasicAsyncAuthentication {

  private static final String RULE_NAME = "external_authentication";
  private static final String ATTRIBUTE_SERVICE = "service";

  private final ExternalAuthenticationServiceConfig config;
  private final RestClient client;

  public static Optional<ExternalAuthenticationAsyncRule> fromSettings(Settings s,
                                                                       List<ExternalAuthenticationServiceConfig> configs)
      throws ConfigMalformedException {
    return ConfigReaderHelper.fromSettings(RULE_NAME, s, parseSimpleSettings(configs), parseExtendedSettings(configs));
  }

  private static Function<Settings, Optional<ExternalAuthenticationAsyncRule>> parseSimpleSettings(
      List<ExternalAuthenticationServiceConfig> configs) {
    return settings -> {
      String name = settings.get(RULE_NAME);
      if(name == null)
        throw new ConfigMalformedException(String.format("No external authentication service name defined in rule %s", RULE_NAME));

      return Optional.of(new ExternalAuthenticationAsyncRule(serviceConfigByName(name, configs)));
    };
  }

  private static Function<Settings, Optional<ExternalAuthenticationAsyncRule>> parseExtendedSettings(
      List<ExternalAuthenticationServiceConfig> configs) {
    return settings -> {
      Map<String, Settings> externalAuths = settings.getGroups(RULE_NAME);
      if (externalAuths.size() == 0) return Optional.empty();

      if (externalAuths.size() != 1)
        throw new ConfigMalformedException(String.format("Only one '%s' is expected within rule's group", RULE_NAME));

      Settings externalAuthSettings = Lists.newArrayList(externalAuths.values()).get(0);
      String externalAuthConfigName = externalAuthSettings.get(ATTRIBUTE_SERVICE);
      if (externalAuthConfigName == null)
        throw new ConfigMalformedException(String.format("No '%s' attribute found in '%s' rule", ATTRIBUTE_SERVICE, RULE_NAME));

      return Optional.of(new ExternalAuthenticationAsyncRule(serviceConfigByName(externalAuthConfigName, configs)));
    };
  }

  private static ExternalAuthenticationServiceConfig serviceConfigByName(String name,
                                                                         List<ExternalAuthenticationServiceConfig> configs) {
    Map<String, ExternalAuthenticationServiceConfig> serviceConfigByName = configs.stream()
        .collect(Collectors.toMap(ExternalAuthenticationServiceConfig::getName, Function.identity()));

    ExternalAuthenticationServiceConfig serviceConfig = serviceConfigByName.get(name);
    if (serviceConfig == null)
      throw new ConfigMalformedException(String.format("There is no external authentication service config with name '%s'", name));

    return serviceConfig;
  }

  private ExternalAuthenticationAsyncRule(ExternalAuthenticationServiceConfig config) {
    this.config = config;
    URI groupBasedAuthEndpoint = config.getEndpoint();
    this.client = RestClient.builder(
        new HttpHost(
            groupBasedAuthEndpoint.getHost(),
            groupBasedAuthEndpoint.getPort()
        )
    ).build();
  }

  @Override
  protected CompletableFuture<Boolean> authenticate(String user, String password) {
    final CompletableFuture<Boolean> promise = new CompletableFuture<>();
    client.performRequestAsync(
        "GET",
        config.getEndpoint().getPath(),
        Maps.newHashMap(),
        new CompletableFutureResponseListener<>(promise,
            response -> response.getStatusLine().getStatusCode() == config.getSuccessStatusCode()),
        BasicAuthUtils.basicAuthHeader(user, password));
    return promise;
  }

  @Override
  public String getKey() {
    return RULE_NAME;
  }
}

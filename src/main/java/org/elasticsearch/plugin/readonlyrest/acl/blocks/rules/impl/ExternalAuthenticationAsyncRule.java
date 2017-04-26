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

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.BasicAsyncAuthentication;
import org.elasticsearch.plugin.readonlyrest.settings.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.es53x.ESContext;
import org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper.notSupported;

public class ExternalAuthenticationAsyncRule extends BasicAsyncAuthentication {

  private static final String RULE_NAME = "external_authentication";
  private static final String ATTRIBUTE_SERVICE = "service";

  private final ExternalAuthenticationServiceConfig config;

  public static Optional<ExternalAuthenticationAsyncRule> fromSettings(Settings s,
                                                                       List<ExternalAuthenticationServiceConfig> configs,
                                                                       ESContext context)
      throws ConfigMalformedException {
    return ConfigReaderHelper.fromSettings(RULE_NAME, s,
        parseSimpleSettings(configs, context),
        notSupported(RULE_NAME),
        parseExtendedSettings(configs, context),
        context);
  }

  private static Function<Settings, Optional<ExternalAuthenticationAsyncRule>> parseSimpleSettings(
      List<ExternalAuthenticationServiceConfig> configs, ESContext context) {
    return settings -> {
      String name = settings.get(RULE_NAME);
      if (name == null)
        throw new ConfigMalformedException(String.format("No external authentication service name defined in rule %s", RULE_NAME));

      return Optional.of(new ExternalAuthenticationAsyncRule(serviceConfigByName(name, configs), context));
    };
  }

  private static Function<Settings, Optional<ExternalAuthenticationAsyncRule>> parseExtendedSettings(
      List<ExternalAuthenticationServiceConfig> configs, ESContext context) {
    return settings -> {
      Settings externalAuthSettings = settings.getAsSettings(RULE_NAME);
      if(externalAuthSettings.isEmpty()) return Optional.empty();

      String externalAuthConfigName = externalAuthSettings.get(ATTRIBUTE_SERVICE);
      if (externalAuthConfigName == null)
        throw new ConfigMalformedException(String.format("No '%s' attribute found in '%s' rule", ATTRIBUTE_SERVICE, RULE_NAME));

      return Optional.of(new ExternalAuthenticationAsyncRule(serviceConfigByName(externalAuthConfigName, configs), context));
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

  private ExternalAuthenticationAsyncRule(ExternalAuthenticationServiceConfig config, ESContext context) {
    super(context);
    this.config = config;
  }

  @Override
  protected CompletableFuture<Boolean> authenticate(String user, String password) {
    return config.getClient().authenticate(user, password);
  }

  @Override
  public String getKey() {
    return RULE_NAME;
  }
}

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

import com.google.common.collect.Lists;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.MatcherWithWildcards;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.UserRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.phantomtypes.Authentication;
import org.elasticsearch.plugin.readonlyrest.wiring.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Created by ah on 15/02/2016.
 */
public class ProxyAuthSyncRule extends SyncRule implements UserRule, Authentication {

  private static final String RULE_NAME = "proxy_auth";
  private static final String NAME_ATTRIBUTE = "name";
  private static final String USERS_ATTRIBUTE = "users";
  private static final String PROXY_AUTH_CONFIG_ATTRIBUTE = "proxy_auth_config";

  private final ProxyAuthConfig config;
  private final MatcherWithWildcards userListMatcher;

  private ProxyAuthSyncRule(ProxyAuthConfig config, List<String> users) {
    this.config = config;
    userListMatcher = new MatcherWithWildcards(
        users.stream()
             .filter(i -> !Strings.isNullOrEmpty(i))
             .distinct()
             .collect(Collectors.toSet())
    );
  }

  public static Optional<ProxyAuthSyncRule> fromSettings(Settings s, List<ProxyAuthConfig> proxyAuthConfigs)
      throws ConfigMalformedException {
    return ConfigReaderHelper.fromSettings(RULE_NAME, s, parseSimpleSettings(),
        parseSimpleArraySettings(), parseExtendedSettings(proxyAuthConfigs));
  }

  private static Function<Settings, Optional<ProxyAuthSyncRule>> parseSimpleSettings() {
    return settings -> {
      List<String> users = Lists.newArrayList(settings.get(RULE_NAME));
      if (users.isEmpty()) return Optional.empty();

      return Optional.of(new ProxyAuthSyncRule(ProxyAuthConfig.DEFAULT, users));
    };
  }

  private static Function<Settings, Optional<ProxyAuthSyncRule>> parseSimpleArraySettings() {
    return settings -> {
      List<String> users = Lists.newArrayList(settings.getAsArray(RULE_NAME));
      if (users.isEmpty()) return Optional.empty();

      return Optional.of(new ProxyAuthSyncRule(ProxyAuthConfig.DEFAULT, users));
    };
  }

  private static Function<Settings, Optional<ProxyAuthSyncRule>> parseExtendedSettings(
      List<ProxyAuthConfig> proxyAuthConfigs) {
    return settings -> {
      Settings proxyAuthSettings = settings.getAsSettings(RULE_NAME);
      if(proxyAuthSettings.isEmpty()) return Optional.empty();

      Map<String, ProxyAuthConfig> proxyAuthConfigByName =
          proxyAuthConfigs.stream().collect(Collectors.toMap(ProxyAuthConfig::getName, Function.identity()));

      String proxyAuthConfigName = proxyAuthSettings.get(PROXY_AUTH_CONFIG_ATTRIBUTE);
      if (proxyAuthConfigName == null)
        throw new ConfigMalformedException(String.format("No '%s' attribute found in '%s' rule", NAME_ATTRIBUTE, RULE_NAME));

      ProxyAuthConfig proxyAuthConfig = proxyAuthConfigByName.get(proxyAuthConfigName);
      if (proxyAuthConfig == null)
        throw new ConfigMalformedException(String.format("There is no proxy auth config with name '%s'", proxyAuthConfigName));

      List<String> users = Lists.newArrayList(proxyAuthSettings.getAsArray(USERS_ATTRIBUTE));
      if (users.isEmpty())
        throw new ConfigMalformedException(String.format("No '%s' attribute found in '%s' rule", USERS_ATTRIBUTE, RULE_NAME));

      return Optional.of(new ProxyAuthSyncRule(proxyAuthConfig, users));
    };
  }

  @Override
  public RuleExitResult match(RequestContext rc) {
    Optional<LoggedUser> optUser = getUser(rc.getHeaders());

    if (!optUser.isPresent()) {
      return NO_MATCH;
    }

    LoggedUser user = optUser.get();
    RuleExitResult res = userListMatcher.match(user.getId()) ? MATCH : NO_MATCH;
    if (res.isMatch()) {
      rc.setLoggedInUser(user);
    }
    return res;
  }

  private Optional<LoggedUser> getUser(Map<String, String> headers) {
    String userId = headers.get(config.getUserIdHeader());
    if (userId == null || userId.trim().length() == 0)
      return Optional.empty();
    return Optional.of(new LoggedUser(userId));
  }

}

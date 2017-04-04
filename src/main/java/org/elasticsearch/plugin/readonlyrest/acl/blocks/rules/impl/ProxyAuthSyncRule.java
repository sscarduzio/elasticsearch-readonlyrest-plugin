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
import org.elasticsearch.common.settings.SettingsException;
import org.elasticsearch.plugin.readonlyrest.acl.LoggedUser;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.MatcherWithWildcards;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.UserRule;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Created by ah on 15/02/2016.
 */
public class ProxyAuthSyncRule extends SyncRule implements UserRule {

  private static final String RULE_NAME = "proxy_auth";
  private static final String NAME_ATTRIBUTE = "name";
  private static final String USERS_ATTRIBUTE = "users";
  private static final String PROXY_AUTH_CONFIG_ATTRIBUTE = "proxy_auth_config";

  private enum ProxyAuthSettingsSchema {
    SIMPLE, EXTENDED
  }

  private final ProxyAuthConfig config;
  private final MatcherWithWildcards userListMatcher;

  public static Optional<ProxyAuthSyncRule> fromSettings(Settings s, List<ProxyAuthConfig> proxyAuthConfigs)
      throws ConfigMalformedException {
    Optional<ProxyAuthSettingsSchema> proxyAuthSettingsSchema = recognizeProxyAuthSettingsSchema(s);
    if (!proxyAuthSettingsSchema.isPresent()) return Optional.empty();
    switch (proxyAuthSettingsSchema.get()) {
      case SIMPLE:
        return parseSimpleSettings(s);
      case EXTENDED:
        return parseExtendedSettings(s, proxyAuthConfigs);
      default:
        throw new IllegalStateException("Unknown auth setting schema");
    }
  }

  private static Optional<ProxyAuthSettingsSchema> recognizeProxyAuthSettingsSchema(Settings s) {
    try {
      s.getGroups(RULE_NAME);
      return Optional.of(ProxyAuthSettingsSchema.EXTENDED);
    } catch (SettingsException ex) {
      if (s.getAsArray(RULE_NAME) != null) {
        return Optional.of(ProxyAuthSettingsSchema.SIMPLE);
      } else {
        return Optional.empty();
      }
    }
  }

  private static Optional<ProxyAuthSyncRule> parseExtendedSettings(Settings s, List<ProxyAuthConfig> proxyAuthConfigs)
      throws ConfigMalformedException {
    Map<String, Settings> proxyAuths = s.getGroups(RULE_NAME);
    if (proxyAuths.size() == 0) return Optional.empty();

    if (proxyAuths.size() != 1)
      throw new ConfigMalformedException(String.format("Only one '%s' is expected within rule's group", RULE_NAME));

    Map<String, ProxyAuthConfig> proxyAuthConfigByName = proxyAuthConfigs.stream()
        .collect(Collectors.toMap(ProxyAuthConfig::getName, Function.identity()));

    Settings proxyAuthSettings = Lists.newArrayList(proxyAuths.values()).get(0);
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
  }

  private static Optional<ProxyAuthSyncRule> parseSimpleSettings(Settings s) {
    List<String> users = Lists.newArrayList(s.getAsArray(RULE_NAME));
    if (users.isEmpty()) return Optional.empty();

    return Optional.of(new ProxyAuthSyncRule(ProxyAuthConfig.DEFAULT, users));
  }

  private ProxyAuthSyncRule(ProxyAuthConfig config, List<String> users) {
    this.config = config;
    userListMatcher = new MatcherWithWildcards(
        users.stream()
            .filter(i -> !Strings.isNullOrEmpty(i))
            .distinct()
            .collect(Collectors.toSet())
    );
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

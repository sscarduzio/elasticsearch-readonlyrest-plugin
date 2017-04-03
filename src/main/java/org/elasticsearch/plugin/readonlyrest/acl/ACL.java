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

package org.elasticsearch.plugin.readonlyrest.acl;

import org.apache.logging.log4j.Logger;
import org.elasticsearch.client.Client;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.ConfigurationHelper;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.Block;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.BlockExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.User;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapConfig;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ProxyAuthConfig;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.UserRoleProviderConfig;
import org.elasticsearch.plugin.readonlyrest.utils.FuturesSequencer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.elasticsearch.plugin.readonlyrest.ConfigurationHelper.ANSI_RED;
import static org.elasticsearch.plugin.readonlyrest.ConfigurationHelper.ANSI_RESET;

/**
 * Created by sscarduzio on 13/02/2016.
 */

public class ACL {
  private static final String RULES_PREFIX = "readonlyrest.access_control_rules";
  private static final String USERS_PREFIX = "readonlyrest.users";
  private static final String LDAPS_PREFIX = "readonlyrest.ldaps";
  private static final String PROXIES_PREFIX = "readonlyrest.proxy_auth_configs";
  private static final String USER_ROLE_PROVIDERS_PREFIX = "readonlyrest.user_role_providers";

  private final Logger logger = Loggers.getLogger(getClass());
  // Array list because it preserves the insertion order
  private ArrayList<Block> blocks = new ArrayList<>();
  private boolean basicAuthConfigured = false;

  public ACL(Client client, ConfigurationHelper conf) {
    Settings s = conf.settings;
    Map<String, Settings> blocksMap = s.getGroups(RULES_PREFIX);
    List<ProxyAuthConfig> proxyAuthConfigs = parseProxyAuthSettings(s.getGroups(PROXIES_PREFIX).values());
    List<User> users = parseUserSettings(s.getGroups(USERS_PREFIX).values(), proxyAuthConfigs);
    List<LdapConfig> ldaps = parseLdapSettings(s.getGroups(LDAPS_PREFIX).values());
    List<UserRoleProviderConfig> roleProviderConfigs = parseUserRoleProviderSettings(s.getGroups(USER_ROLE_PROVIDERS_PREFIX).values());
    blocksMap.entrySet()
        .forEach(entry -> {
          Block block = new Block(entry.getValue(), users, ldaps, proxyAuthConfigs, roleProviderConfigs, logger);
          blocks.add(block);
          if (block.isAuthHeaderAccepted()) {
            basicAuthConfigured = true;
          }
          logger.info("ADDING #" + entry.getKey() + ":\t" + block.toString());
        });
  }

  public boolean isBasicAuthConfigured() {
    return basicAuthConfigured;
  }

  public CompletableFuture<BlockExitResult> check(RequestContext rc) {
    logger.debug("checking request:" + rc);
    return FuturesSequencer.runInSeqUntilConditionIsUndone(
        blocks.iterator(),
        block -> block.check(rc),
        checkResult -> {
          if (checkResult.isMatch()) {
            logger.info("request: " + rc + " matched block: " + checkResult);
            return true;
          } else {
            return false;
          }
        },
        nothing -> {
          logger.info(ANSI_RED + " no block has matched, forbidding by default: " + rc + ANSI_RESET);
          return BlockExitResult.noMatch();
        }
    );
  }

  private List<User> parseUserSettings(Collection<Settings> userSettings, List<ProxyAuthConfig> proxyAuthConfigs) {
    return userSettings.stream()
        .map(settings -> User.fromSettings(settings, proxyAuthConfigs))
        .collect(Collectors.toList());
  }

  private List<LdapConfig> parseLdapSettings(Collection<Settings> ldapSettings) {
    return ldapSettings.stream()
        .map(LdapConfig::fromSettings)
        .collect(Collectors.toList());
  }

  private List<ProxyAuthConfig> parseProxyAuthSettings(Collection<Settings> proxyAuthSettings) {
    return proxyAuthSettings.stream()
        .map(ProxyAuthConfig::fromSettings)
        .collect(Collectors.toList());
  }

  private List<UserRoleProviderConfig> parseUserRoleProviderSettings(Collection<Settings> roleProvidersSettings) {
    return roleProvidersSettings.stream()
        .map(UserRoleProviderConfig::fromSettings)
        .collect(Collectors.toList());
  }
}

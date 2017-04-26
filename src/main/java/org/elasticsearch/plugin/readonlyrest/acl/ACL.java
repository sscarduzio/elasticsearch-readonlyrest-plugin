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
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.ConfigurationHelper;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.Block;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.BlockExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.LdapConfigs;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.User;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ExternalAuthenticationServiceConfig;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ProxyAuthConfig;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.UserGroupProviderConfig;
import org.elasticsearch.plugin.readonlyrest.wiring.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.es53x.ESContext;
import org.elasticsearch.plugin.readonlyrest.utils.FuturesSequencer;
import org.elasticsearch.plugin.readonlyrest.wiring.requestcontext.Verbosity;

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
  private static final String USER_GROUPS_PROVIDERS_PREFIX = "readonlyrest.user_groups_providers";
  private static final String EXTERNAL_AUTH_SERVICES_PREFIX = "readonlyrest.external_authentication_service_configs";

  private final Logger logger;
  private final ESContext context;
  // Array list because it preserves the insertion order
  private final ArrayList<Block> blocks = new ArrayList<>();

  public ACL(ConfigurationHelper conf, ESContext context) {
    this.context = context;
    this.logger = context.logger(getClass());
    Settings s = conf.settings;
    Map<String, Settings> blocksMap = s.getGroups(RULES_PREFIX);
    List<ProxyAuthConfig> proxyAuthConfigs = parseProxyAuthSettings(s.getGroups(PROXIES_PREFIX).values());
    List<User> users = parseUserSettings(s.getGroups(USERS_PREFIX).values(), proxyAuthConfigs);
    LdapConfigs ldaps = LdapConfigs.fromSettings(LDAPS_PREFIX, s, context);
    List<UserGroupProviderConfig> groupsProviderConfigs = parseUserGroupsProviderSettings(
        s.getGroups(USER_GROUPS_PROVIDERS_PREFIX).values()
    );
    List<ExternalAuthenticationServiceConfig> externalAuthenticationServiceConfigs =
        parseExternalAuthenticationServiceSettings(s.getGroups(EXTERNAL_AUTH_SERVICES_PREFIX).values());
    blocksMap.forEach((key, value) -> {
               Block block = new Block(value, users, ldaps, proxyAuthConfigs, groupsProviderConfigs,
                   externalAuthenticationServiceConfigs, context);
               blocks.add(block);
               if (block.isAuthHeaderAccepted()) {
                 ConfigurationHelper.setRequirePassword( true);
               }
               logger.info("ADDING #" + key + ":\t" + block.toString());
             });
  }

  public CompletableFuture<BlockExitResult> check(RequestContext rc) {
    logger.debug("checking request:" + rc.getId());
    return FuturesSequencer.runInSeqUntilConditionIsUndone(
        blocks.iterator(),
        block -> {
          rc.reset();
          return block.check(rc);
        },
        checkResult -> {
          Verbosity v = rc.getVerbosity();
          if (checkResult.isMatch()) {
            if(v.equals(Verbosity.INFO)){
              logger.info("request: " + rc + " matched block: " + checkResult);
            }
            rc.commit();
            return true;
          }
          else {
            return false;
          }
        },
        nothing -> {
          Verbosity v = rc.getVerbosity();
          if(v.equals(Verbosity.INFO) || v.equals(Verbosity.ERROR)){
            logger.info(ANSI_RED + " no block has matched, forbidding by default: " + rc + ANSI_RESET);
          }
          return BlockExitResult.noMatch();
        }
    );
  }

  private List<User> parseUserSettings(Collection<Settings> userSettings, List<ProxyAuthConfig> proxyAuthConfigs) {
    return userSettings.stream()
                       .map(settings -> User.fromSettings(settings, proxyAuthConfigs, context))
                       .collect(Collectors.toList());
  }

  private List<ProxyAuthConfig> parseProxyAuthSettings(Collection<Settings> proxyAuthSettings) {
    return proxyAuthSettings.stream()
                            .map(ProxyAuthConfig::fromSettings)
                            .collect(Collectors.toList());
  }

  private List<UserGroupProviderConfig> parseUserGroupsProviderSettings(Collection<Settings> groupProvidersSettings) {
    return groupProvidersSettings.stream()
                                 .map(UserGroupProviderConfig::fromSettings)
                                 .collect(Collectors.toList());
  }

  private List<ExternalAuthenticationServiceConfig> parseExternalAuthenticationServiceSettings(
      Collection<Settings> ExternalAuthenticationServiceSettings) {
    return ExternalAuthenticationServiceSettings.stream()
        .map(ExternalAuthenticationServiceConfig::fromSettings)
        .collect(Collectors.toList());
  }
}

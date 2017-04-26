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
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.LdapConfigs;
import org.elasticsearch.plugin.readonlyrest.es53x.ESContext;
import org.elasticsearch.plugin.readonlyrest.ldap.AuthenticationLdapClient;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapCredentials;
import org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.elasticsearch.plugin.readonlyrest.utils.ConfigReaderHelper.notSupported;

public class LdapAuthenticationAsyncRule extends BasicAsyncAuthentication {

  private static final String RULE_NAME = "ldap_authentication";
  private static final String LDAP_NAME = "name";

  private final AuthenticationLdapClient client;

  LdapAuthenticationAsyncRule(AuthenticationLdapClient client, ESContext context) {
    super(context);
    this.client = client;
  }

  public static Optional<LdapAuthenticationAsyncRule> fromSettings(Settings s, LdapConfigs ldapConfigs, ESContext context)
      throws ConfigMalformedException {
    return ConfigReaderHelper.fromSettings(RULE_NAME, s,
        fromSimpleSettings(ldapConfigs, context),
        notSupported(RULE_NAME),
        fromExtendedSettings(ldapConfigs, context),
        context);
  }

  private static Function<Settings, Optional<LdapAuthenticationAsyncRule>> fromSimpleSettings(LdapConfigs ldapConfigs,
                                                                                              ESContext context) {
    return settings -> {
      String ldapName = settings.get(RULE_NAME);
      if (ldapName == null)
        throw new ConfigMalformedException("No [" + LDAP_NAME + "] req defined");

      return tryCreateRule(ldapName, context, ldapConfigs);
    };
  }

  private static Function<Settings, Optional<LdapAuthenticationAsyncRule>> fromExtendedSettings(LdapConfigs ldapConfigs,
                                                                                                ESContext context) {
    return settings -> {
      Settings ldapSettings = settings.getAsSettings(RULE_NAME);
      if(ldapSettings.isEmpty()) return Optional.empty();

      String ldapName = ldapSettings.get(LDAP_NAME);
      if (ldapName == null)
        throw new ConfigMalformedException("No [" + LDAP_NAME + "] attribute defined");

      return tryCreateRule(ldapName, context, ldapConfigs);
    };
  }

  private static Optional<LdapAuthenticationAsyncRule> tryCreateRule(String ldapName, ESContext context,
                                                                     LdapConfigs ldapConfigs) {
    return Optional.of(new LdapAuthenticationAsyncRule(ldapConfigs.authenticationLdapClientForName(ldapName), context));
  }

  @Override
  protected CompletableFuture<Boolean> authenticate(String user, String password) {
    return client
        .authenticate(new LdapCredentials(user, password))
        .thenApply(Optional::isPresent);
  }

  @Override
  public String getKey() {
    return RULE_NAME;
  }

}

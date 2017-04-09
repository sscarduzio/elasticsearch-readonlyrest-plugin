package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.BasicAsyncAuthentication;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapClient;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapCredentials;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class LdapAuthenticationAsyncRule extends BasicAsyncAuthentication {

  private static final String RULE_NAME = "ldap_authentication";
  private final LdapClient client;

  public static Optional<LdapAuthenticationAsyncRule> fromSettings(Settings s,
                                                                   List<LdapConfig> ldapConfigs) throws ConfigMalformedException {
    Map<String, LdapClient> ldapClientsByName = ldapConfigs.stream()
        .collect(Collectors.toMap(LdapConfig::getName, LdapConfig::getClient));

    return Optional.ofNullable(s.get(RULE_NAME))
        .flatMap(ldapName -> Optional.ofNullable(ldapClientsByName.get(ldapName)))
        .map(LdapAuthenticationAsyncRule::new);
  }

  LdapAuthenticationAsyncRule(LdapClient client) {
    this.client = client;
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

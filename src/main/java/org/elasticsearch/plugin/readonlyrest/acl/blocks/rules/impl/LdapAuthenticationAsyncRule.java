package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import com.google.common.collect.Lists;
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
  private static final String LDAP_NAME = "name";

  private final LdapClient client;

  public static Optional<LdapAuthenticationAsyncRule> fromSettings(Settings s,
                                                                   List<LdapConfig> ldapConfigs) throws ConfigMalformedException {
    Map<String, Settings> ldapAuths = s.getGroups(RULE_NAME);
    if (ldapAuths.isEmpty()) return Optional.empty();
    if (ldapAuths.size() != 1)
      throw new ConfigMalformedException("Only one [" + RULE_NAME + "] in group could be defined");

    Map<String, LdapClient> ldapClientsByName = ldapConfigs.stream()
        .collect(Collectors.toMap(LdapConfig::getName, LdapConfig::getClient));

    Settings ldapSettings = Lists.newArrayList(ldapAuths.values()).get(0);
    String name = ldapSettings.get(LDAP_NAME);
    if (name == null)
      throw new ConfigMalformedException("No [" + LDAP_NAME + "] attribute defined");
    if (!ldapClientsByName.containsKey(name)) {
      throw new ConfigMalformedException("LDAP with name [" + name + "] wasn't defined.");
    }
    return Optional.of(new LdapAuthenticationAsyncRule(ldapClientsByName.get(name)));
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

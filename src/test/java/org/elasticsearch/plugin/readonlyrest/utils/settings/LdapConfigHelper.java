package org.elasticsearch.plugin.readonlyrest.utils.settings;

import com.google.common.collect.Sets;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapConfig;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapClient;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapGroup;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapUser;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class LdapConfigHelper {

  public static LdapConfig mockLdapConfig(String name) {
    return mockLdapConfig(name, Optional.empty());
  }

  public static LdapConfig mockLdapConfig(String name, Optional<Tuple<LdapUser, Set<LdapGroup>>> onAuthenticate) {
    LdapConfig config = mock(LdapConfig.class);
    when(config.getName()).thenReturn(name);
    LdapClient client = mock(LdapClient.class);
    if(onAuthenticate.isPresent()) {
      LdapUser user = onAuthenticate.map(Tuple::v1).get();
      Set<LdapGroup> groups = onAuthenticate.map(Tuple::v2).get();
      when(client.authenticate(any())).thenReturn(CompletableFuture.completedFuture(Optional.of(user)));
      when(client.userGroups(user)).thenReturn(CompletableFuture.completedFuture(groups));
      when(client.userById(user.getUid())).thenReturn(CompletableFuture.completedFuture(Optional.of(user)));
    } else {
      when(client.authenticate(any())).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
      when(client.userGroups(any())).thenReturn(CompletableFuture.completedFuture(Sets.newHashSet()));
      when(client.userById(any())).thenReturn(CompletableFuture.completedFuture(Optional.empty()));
    }
    when(config.getClient()).thenReturn(client);
    return config;
  }
}

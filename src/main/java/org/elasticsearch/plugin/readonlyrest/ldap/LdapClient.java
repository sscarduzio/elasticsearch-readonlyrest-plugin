package org.elasticsearch.plugin.readonlyrest.ldap;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface LdapClient {

    CompletableFuture<Optional<LdapUser>> authenticate(LdapCredentials credentials);
}

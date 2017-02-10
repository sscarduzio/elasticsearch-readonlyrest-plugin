package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleNotConfiguredException;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapClient;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapClientWithCacheDecorator;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapCredentials;
import org.elasticsearch.plugin.readonlyrest.ldap.UnboundidLdapClient;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class LdapAuthAsyncRule extends GeneralAuthKeyAsyncRule {

    private final LdapClient ldapClient;

    public LdapAuthAsyncRule(Settings s) throws RuleNotConfiguredException {
        super(s);
        if (s.get(this.getKey()) == null) {
            throw new RuleNotConfiguredException();
        }
        // todo: from elasticsearch config
        this.ldapClient = new LdapClientWithCacheDecorator(
                new UnboundidLdapClient(
                        "ldap.touk.pl",
                        636,
                        "ou=Touki,ou=People,dc=touk,dc=pl",
                        "ou=Group,dc=touk,dc=pl",
                        10,
                        Duration.ofMinutes(1)),
                Duration.ofMinutes(1)
        );
    }

    @Override
    protected CompletableFuture<Boolean> authenticate(String user, String password) {
        // todo: groups check
        return ldapClient.authenticate(new LdapCredentials(user, password))
                .thenApply(Optional::isPresent);
    }

}

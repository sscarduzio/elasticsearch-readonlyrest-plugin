package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapClient;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapCredentials;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapGroup;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapUser;
import org.elasticsearch.plugin.readonlyrest.utils.FuturesSequencer;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class LdapAuthAsyncRule extends GeneralAuthKeyAsyncRule {

    private static final String RULE_NAME = "ldap_auth";
    private static final String LDAP_NAME = "name";
    private static final String LDAP_GROUP_NAMES = "groups";

    private final List<LdapAuthDefinition> ldapAuthDefinitions;

    public static Optional<LdapAuthAsyncRule> fromSettings(Settings s, List<LdapConfig> ldapConfigs) throws ConfigMalformedException {
        Map<String, Settings> ldapAuths = s.getGroups(RULE_NAME);
        if (ldapAuths.isEmpty()) return Optional.empty();

        Map<String, LdapClient> ldapClientsByName = ldapConfigs.stream()
                .collect(Collectors.toMap(LdapConfig::getName, LdapConfig::getClient));

        List<LdapAuthDefinition> authDefinitions =
                ldapAuths.values().stream()
                        .map(ldapAuthDefSettings -> {
                                    String name = ldapAuthDefSettings.get(LDAP_NAME);
                                    if (name == null)
                                        throw new ConfigMalformedException("No [" + LDAP_NAME + "] attribute defined");
                                    Set<String> groups = Sets.newHashSet(ldapAuthDefSettings.getAsArray(LDAP_GROUP_NAMES));
                                    if (!ldapClientsByName.containsKey(name)) {
                                        throw new ConfigMalformedException("LDAP with name [" + name + "] wasn't defined.");
                                    }
                                    return new LdapAuthDefinition(ldapClientsByName.get(name), groups);
                                }
                        )
                        .collect(Collectors.toList());
        return Optional.of(new LdapAuthAsyncRule(authDefinitions));
    }

    private LdapAuthAsyncRule(List<LdapAuthDefinition> ldapAuthDefinitions) {
        this.ldapAuthDefinitions = ldapAuthDefinitions;
    }

    @Override
    protected CompletableFuture<Boolean> authenticate(String user, String password) {
        LdapCredentials credentials = new LdapCredentials(user, password);
        return FuturesSequencer.runInSeqUntilConditionIsUndone(
                ldapAuthDefinitions.iterator(),
                authDefinition -> authDefinition.client.authenticate(credentials),
                (authDefinition, ldapUser) -> ldapUser.isPresent() && checkIfUserHasAccess(ldapUser.get(), authDefinition),
                noMatterWhat -> true,
                noMatterWhat -> false
        );
    }

    private boolean checkIfUserHasAccess(LdapUser user, LdapAuthDefinition authDefinition) {
        return authDefinition.accessGroups.isEmpty() ||
                !Sets.intersection(
                        authDefinition.accessGroups,
                        user.getGroups().stream().map(LdapGroup::getName).collect(Collectors.toSet())
                ).isEmpty();
    }

    @Override
    public String getKey() {
        return RULE_NAME;
    }

    private static class LdapAuthDefinition {
        private final LdapClient client;
        private final ImmutableSet<String> accessGroups;

        LdapAuthDefinition(LdapClient client, Set<String> accessGroups) {
            this.client = client;
            this.accessGroups = ImmutableSet.copyOf(accessGroups);
        }
    }
}

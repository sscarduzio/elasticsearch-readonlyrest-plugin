package org.elasticsearch.plugin.readonlyrest.ldap;

import com.google.common.collect.ImmutableSet;

import java.util.Set;

public class LdapUser {
    private final String uid;
    private final String dn;
    private final ImmutableSet<LdapGroup> groups;

    public LdapUser(String uid, String dn, Set<LdapGroup> groups) {
        this.uid = uid;
        this.dn = dn;
        this.groups = ImmutableSet.copyOf(groups);
    }

    public LdapUser(String uid, String dn) {
        this(uid, dn, ImmutableSet.of());
    }

    public String getDN() {
        return dn;
    }

    public ImmutableSet<LdapGroup> getGroups() {
        return groups;
    }

    public LdapUser withGroups(Set<LdapGroup> groups) {
        return new LdapUser(uid, dn, groups);
    }
}

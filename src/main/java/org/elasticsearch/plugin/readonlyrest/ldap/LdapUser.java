package org.elasticsearch.plugin.readonlyrest.ldap;

import com.google.common.collect.ImmutableSet;

import java.util.Set;

public class LdapUser {
    private final String uid;
    private final String cn;
    private final ImmutableSet<LdapGroup> groups;

    public LdapUser(String uid, String cn, Set<LdapGroup> groups) {
        this.uid = uid;
        this.cn = cn;
        this.groups = ImmutableSet.copyOf(groups);
    }

    public LdapUser(String uid, String cn) {
        this(uid, cn, ImmutableSet.of());
    }

    public String getCn() {
        return cn;
    }

    public ImmutableSet<LdapGroup> getGroups() {
        return groups;
    }

    public LdapUser withGroups(Set<LdapGroup> groups) {
        return new LdapUser(uid, cn, groups);
    }
}

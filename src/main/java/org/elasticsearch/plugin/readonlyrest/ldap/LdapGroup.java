package org.elasticsearch.plugin.readonlyrest.ldap;

public class LdapGroup {
    private final String name;

    public LdapGroup(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}

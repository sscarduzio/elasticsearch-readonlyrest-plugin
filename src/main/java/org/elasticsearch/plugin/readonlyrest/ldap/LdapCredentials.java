package org.elasticsearch.plugin.readonlyrest.ldap;

import java.util.Objects;

public class LdapCredentials {

    private final String userName;
    private final String password;

    public LdapCredentials(String userName, String password) {
        this.userName = userName;
        this.password = password;
    }

    public String getUserName() {
        return userName;
    }

    public String getPassword() {
        return password;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null) return false;
        if (getClass() != obj.getClass()) return false;
        final LdapCredentials other = (LdapCredentials) obj;
        return Objects.equals(this.userName, other.userName)
                && Objects.equals(this.password, other.password);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.userName, this.password);
    }
}

package org.elasticsearch.plugin.readonlyrest.ldap;

public class LdapClientInitializationException extends RuntimeException {
    public LdapClientInitializationException(String message, Throwable inner) {
        super(message, inner);
    }
}

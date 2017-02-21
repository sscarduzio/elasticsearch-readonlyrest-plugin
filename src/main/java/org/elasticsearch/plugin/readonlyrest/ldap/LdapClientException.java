package org.elasticsearch.plugin.readonlyrest.ldap;

public class LdapClientException {
    private LdapClientException() {}

    public static class InitializationException extends RuntimeException {
        public InitializationException(String message, Throwable inner) {
            super(message, inner);
        }
        public InitializationException(String message) {
            super(message);
        }
    }

    public static class SearchException extends RuntimeException {
        public SearchException(Throwable inner) {
            super(inner);
        }
    }
}

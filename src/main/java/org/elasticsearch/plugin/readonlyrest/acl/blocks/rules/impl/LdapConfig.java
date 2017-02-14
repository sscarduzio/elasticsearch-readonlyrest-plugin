package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapClient;
import org.elasticsearch.plugin.readonlyrest.ldap.LdapClientWithCacheDecorator;
import org.elasticsearch.plugin.readonlyrest.ldap.UnboundidLdapClient;

import java.time.Duration;
import java.util.Optional;

public class LdapConfig {
    private static int DEFAULT_LDAP_PORT = 389;
    private static int DEFAULT_LDAP_CONNECTION_POOL_SIZE = 10;
    private static Duration DEFAULT_LDAP_REQUEST_TIMEOUT = Duration.ofSeconds(1);
    private static Duration DEFAULT_LDAP_CONNECTION_TIMEOUT = Duration.ofSeconds(1);
    private static Duration DEFAULT_LDAP_CACHE_TTL = Duration.ZERO;
    private static boolean DEFAULT_LDAP_SSL_ENABLED = true;
    private static boolean DEFAULT_LDAP_SSL_TRUST_ALL_CERTS = false;

    private static String ATTRIBUTE_NAME = "name";
    private static String ATTRIBUTE_HOST = "host";
    private static String ATTRIBUTE_PORT = "port";
    private static String ATTRIBUTE_BIND_DN = "bind_dn";
    private static String ATTRIBUTE_BIND_PASSWORD = "bind_password";
    private static String ATTRIBUTE_SEARCH_USER_BASE_DN = "search_user_base_DN";
    private static String ATTRIBUTE_SEARCH_GROUPS_BASE_DN = "search_groups_base_DN";
    private static String ATTRIBUTE_CONNECTION_POOL_SIZE = "connection_pool_size";
    private static String ATTRIBUTE_CONNECTION_TIMEOUT = "connection_timeout_in_sec";
    private static String ATTRIBUTE_REQUEST_TIMEOUT = "request_timeout_in_sec";
    private static String ATTRIBUTE_CACHE_TTL = "cache_ttl_in_sec";
    private static String ATTRIBUTE_SSL_ENABLED = "ssl_enabled";
    private static String ATTRIBUTE_SSL_TRUST_ALL_CERTS = "ssl_trust_all_certs";

    private final String name;
    private final LdapClient client;

    private LdapConfig(String name, LdapClient client) {
        this.name = name;
        this.client = client;
    }

    public String getName() {
        return name;
    }

    public LdapClient getClient() {
        return client;
    }

    public static LdapConfig fromSettings(Settings s) throws ConfigMalformedException {
        String name = s.get(ATTRIBUTE_NAME);
        if(name == null) throw new ConfigMalformedException("LDAP definition malformed - no [" + ATTRIBUTE_NAME + "] attribute");
        String host = s.get(ATTRIBUTE_HOST);
        if(host == null) throw new ConfigMalformedException("LDAP definition malformed - no [" + ATTRIBUTE_HOST + "] attribute defined for LDAP [" + name + "]");
        boolean sslEnabled = s.getAsBoolean(ATTRIBUTE_SSL_ENABLED, DEFAULT_LDAP_SSL_ENABLED);
        boolean trustAllCerts = s.getAsBoolean(ATTRIBUTE_SSL_TRUST_ALL_CERTS, DEFAULT_LDAP_SSL_TRUST_ALL_CERTS);
        Optional<String> bindDn = Optional.ofNullable(s.get(ATTRIBUTE_BIND_DN));
        Optional<String> bindPassword = Optional.ofNullable(s.get(ATTRIBUTE_BIND_PASSWORD));
        Optional<UnboundidLdapClient.BindDnPassword> bindDnPassword;
        if(bindDn.isPresent() && bindPassword.isPresent()) {
            bindDnPassword = Optional.of(new UnboundidLdapClient.BindDnPassword(bindDn.get(), bindPassword.get()));
        } else if(!bindDn.isPresent() && !bindPassword.isPresent()) {
            bindDnPassword = Optional.empty();
        } else {
            throw new ConfigMalformedException("LDAP definition malformed - must configure both params [" + ATTRIBUTE_BIND_DN + ", " + ATTRIBUTE_BIND_PASSWORD +"]");
        }
        String searchUserBaseDn = s.get(ATTRIBUTE_SEARCH_USER_BASE_DN);
        if(searchUserBaseDn == null) throw new ConfigMalformedException("LDAP definition malformed - no [" + ATTRIBUTE_SEARCH_USER_BASE_DN + "] attribute defined for LDAP [" + name + "]");
        String searchGroupsBaseDn = s.get(ATTRIBUTE_SEARCH_GROUPS_BASE_DN);
        if(searchGroupsBaseDn == null) throw new ConfigMalformedException("LDAP definition malformed - no [" + ATTRIBUTE_SEARCH_GROUPS_BASE_DN + "] attribute defined for LDAP [" + name + "]");
        int port = s.getAsInt(ATTRIBUTE_PORT, DEFAULT_LDAP_PORT);
        int poolSize = s.getAsInt(ATTRIBUTE_CONNECTION_POOL_SIZE, DEFAULT_LDAP_CONNECTION_POOL_SIZE);
        Duration connectionTimeout = Duration.ofSeconds(s.getAsLong(ATTRIBUTE_CONNECTION_TIMEOUT, DEFAULT_LDAP_CONNECTION_TIMEOUT.getSeconds()));
        Duration requestTimeout = Duration.ofSeconds(s.getAsLong(ATTRIBUTE_REQUEST_TIMEOUT, DEFAULT_LDAP_REQUEST_TIMEOUT.getSeconds()));
        Duration cacheTtl = Duration.ofSeconds(s.getAsLong(ATTRIBUTE_CACHE_TTL, DEFAULT_LDAP_CACHE_TTL.getSeconds()));

        LdapClient client = new UnboundidLdapClient(host, port, bindDnPassword, searchUserBaseDn, searchGroupsBaseDn, poolSize,
                connectionTimeout, requestTimeout, sslEnabled, trustAllCerts);

        return new LdapConfig(name,
                cacheTtl.isZero() ? client : new LdapClientWithCacheDecorator(client, cacheTtl));
    }

}

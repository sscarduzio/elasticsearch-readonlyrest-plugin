/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */

package org.elasticsearch.plugin.readonlyrest.ldap;

import com.google.common.collect.Lists;
import com.unboundid.ldap.sdk.AsyncRequestID;
import com.unboundid.ldap.sdk.BindResult;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPConnectionOptions;
import com.unboundid.ldap.sdk.LDAPConnectionPool;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SearchRequest;
import com.unboundid.ldap.sdk.SearchResult;
import com.unboundid.ldap.sdk.SearchResultEntry;
import com.unboundid.ldap.sdk.SearchResultReference;
import com.unboundid.ldap.sdk.SearchScope;
import com.unboundid.ldap.sdk.SimpleBindRequest;
import com.unboundid.util.ssl.SSLUtil;
import com.unboundid.util.ssl.TrustAllTrustManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.logging.Loggers;

import javax.net.ssl.SSLSocketFactory;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class UnboundidLdapClient implements LdapClient {
    private static final Logger logger = Loggers.getLogger(UnboundidLdapClient.class);

    public static int DEFAULT_LDAP_PORT = 389;
    public static int DEFAULT_LDAP_CONNECTION_POOL_SIZE = 30;
    public static Duration DEFAULT_LDAP_REQUEST_TIMEOUT = Duration.ofSeconds(1);
    public static Duration DEFAULT_LDAP_CONNECTION_TIMEOUT = Duration.ofSeconds(1);
    public static Duration DEFAULT_LDAP_CACHE_TTL = Duration.ZERO;
    public static boolean DEFAULT_LDAP_SSL_ENABLED = true;
    public static boolean DEFAULT_LDAP_SSL_TRUST_ALL_CERTS = false;

    private final String searchUserBaseDN;
    private final String searchGroupBaseDN;
    private final LDAPConnectionPool connectionPool;
    private final Long timeout;

    private UnboundidLdapClient(String host,
                                int port,
                                Optional<BindDnPassword> bindDnPassword,
                                String searchUserBaseDN,
                                String searchGroupBaseDN,
                                int poolSize,
                                Duration connectionTimeout,
                                Duration requestTimeout,
                                boolean sslEnabled,
                                boolean trustAllCerts) {
        this.searchUserBaseDN = searchUserBaseDN;
        this.searchGroupBaseDN = searchGroupBaseDN;
        this.timeout = requestTimeout.toMillis();

        try {
            LDAPConnectionOptions options = new LDAPConnectionOptions();
            options.setConnectTimeoutMillis((int) connectionTimeout.toMillis());
            options.setResponseTimeoutMillis(requestTimeout.toMillis());
            LDAPConnection connection;
            if (sslEnabled) {
                SSLUtil sslUtil = trustAllCerts ? new SSLUtil(new TrustAllTrustManager()) : new SSLUtil();
                SSLSocketFactory sslSocketFactory = sslUtil.createSSLSocketFactory();
                connection = new LDAPConnection(sslSocketFactory, options);
            } else {
                connection = new LDAPConnection(options);
            }
            connection.connect(host, port, (int) connectionTimeout.toMillis());

            if (bindDnPassword.isPresent()) {
                BindDnPassword dnPassword = bindDnPassword.get();
                BindResult result = connection.bind(dnPassword.getDn(), dnPassword.getPassword());
                if (!ResultCode.SUCCESS.equals(result.getResultCode())) {
                    throw new LdapClientException.InitializationException("LDAP binding problem - returned [" +
                            result.getResultString() + "]");
                }
            }
            connectionPool = new LDAPConnectionPool(connection, poolSize);
        } catch (GeneralSecurityException e) {
            throw new LdapClientException.InitializationException("SSL Factory creation problem", e);
        } catch (LDAPException e) {
            throw new LdapClientException.InitializationException("LDAP connection problem", e);
        }
    }

    @Override
    public CompletableFuture<Optional<LdapUser>> authenticate(LdapCredentials credentials) {
        return getUser(credentials.getUserName())
                .thenCompose(user -> {
                    if (user == null || !user.isPresent()) {
                        return CompletableFuture.completedFuture(Optional.empty());
                    } else {
                        return getUserGroups(user.get());
                    }
                })
                .thenApply(userWithGroups -> {
                    if (userWithGroups != null && userWithGroups.isPresent() &&
                            authenticate(userWithGroups.get(), credentials.getPassword())) {
                        return userWithGroups;
                    } else {
                        return Optional.empty();
                    }
                });
    }

    private CompletableFuture<Optional<LdapUser>> getUser(String uid) {
        try {
            CompletableFuture<List<SearchResultEntry>> searchUser = new CompletableFuture<>();
            connectionPool.processRequestsAsync(Lists.newArrayList(
                    new SearchRequest(
                            new UnboundidSearchResultListener(searchUser),
                            searchUserBaseDN,
                            SearchScope.SUB,
                            String.format("(uid=%s)", uid)
                    )),
                    timeout);
            return searchUser
                    .thenApply(userSearchResult -> {
                        if (userSearchResult != null && userSearchResult.size() > 0) {
                            return Optional.of(new LdapUser(uid, userSearchResult.get(0).getDN()));
                        } else {
                            logger.debug("LDAP getting user CN returned no entries");
                            return Optional.<LdapUser>empty();
                        }
                    })
                    .exceptionally(t -> {
                        if (t.getCause() instanceof LdapSearchError) {
                            LdapSearchError error = (LdapSearchError) t.getCause();
                            logger.debug(String.format("LDAP getting user CN returned error [%s]", error.getResultString()));
                            return Optional.empty();
                        }
                        throw new LdapClientException.SearchException(t);
                    });
        } catch (LDAPException e) {
            logger.error("LDAP getting user operation failed", e);
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    private CompletableFuture<Optional<LdapUser>> getUserGroups(LdapUser user) {
        try {
            CompletableFuture<List<SearchResultEntry>> searchGroups = new CompletableFuture<>();
            connectionPool.processRequestsAsync(Lists.newArrayList(
                    new SearchRequest(
                            new UnboundidSearchResultListener(searchGroups),
                            searchGroupBaseDN,
                            SearchScope.SUB,
                            String.format("(&(cn=*)(uniqueMember=%s))", user.getDN())
                    )),
                    timeout
            );
            return searchGroups
                    .thenApply(groupSearchResult -> {
                        Set<LdapGroup> groups = groupSearchResult.stream()
                                .map(it -> Optional.ofNullable(it.getAttributeValue("cn")))
                                .filter(Optional::isPresent)
                                .map(Optional::get)
                                .map(LdapGroup::new)
                                .collect(Collectors.toSet());
                        if (groups.isEmpty()) {
                            return Optional.of(user);
                        } else {
                            return Optional.of(user.withGroups(groups));
                        }
                    })
                    .exceptionally(t -> {
                        if (t instanceof LdapSearchError) {
                            LdapSearchError error = (LdapSearchError) t;
                            logger.debug(String.format("LDAP getting user groups returned error [%s]", error.getResultString()));
                        }
                        return Optional.empty();
                    });
        } catch (LDAPException e) {
            logger.error("LDAP getting user groups operation failed", e);
            return CompletableFuture.completedFuture(Optional.empty());
        }
    }

    private Boolean authenticate(LdapUser user, String password) {
        LDAPConnection connection = null;
        try {
            connection = connectionPool.getConnection();
            BindResult result = connection.bind(new SimpleBindRequest(user.getDN(), password));
            return ResultCode.SUCCESS.equals(result.getResultCode());
        } catch (LDAPException e) {
            logger.error("LDAP authenticate operation failed");
            return false;
        } finally {
            if (connection != null) {
                connectionPool.releaseAndReAuthenticateConnection(connection);
            }
        }
    }

    private static class LdapSearchError extends RuntimeException {
        private final ResultCode code;
        private final String resultString;

        LdapSearchError(ResultCode code, String resultString) {
            this.code = code;
            this.resultString = resultString;
        }

        public ResultCode getCode() {
            return code;
        }

        public String getResultString() {
            return resultString;
        }
    }

    private static class UnboundidSearchResultListener
            implements com.unboundid.ldap.sdk.AsyncSearchResultListener {

        private final CompletableFuture<List<SearchResultEntry>> futureToComplete;
        private final ArrayList<SearchResultEntry> searchResultEntries = Lists.newArrayList();

        UnboundidSearchResultListener(CompletableFuture<List<SearchResultEntry>> futureToComplete) {
            this.futureToComplete = futureToComplete;
        }

        @Override
        public void searchResultReceived(AsyncRequestID requestID, SearchResult searchResult) {
            if (ResultCode.SUCCESS.equals(searchResult.getResultCode())) {
                futureToComplete.complete(searchResultEntries);
            } else {
                futureToComplete.completeExceptionally(
                        new LdapSearchError(searchResult.getResultCode(), searchResult.getResultString())
                );
            }
        }

        @Override
        public void searchEntryReturned(SearchResultEntry searchEntry) {
            searchResultEntries.add(searchEntry);
        }

        @Override
        public void searchReferenceReturned(SearchResultReference searchReference) {
            // nothing to do
        }
    }

    public static class BindDnPassword {
        private final String dn;
        private final String password;

        public BindDnPassword(String dn, String password) {
            this.dn = dn;
            this.password = password;
        }

        public String getDn() {
            return dn;
        }

        public String getPassword() {
            return password;
        }
    }

    public static class Builder {
        private final String host;
        private final String searchUserBaseDN;
        private final String searchGroupBaseDN;
        private int port;
        private Optional<BindDnPassword> bindDnPassword = Optional.empty();
        private int poolSize;
        private Duration connectionTimeout;
        private Duration requestTimeout;
        private boolean sslEnabled;
        private boolean trustAllCerts;

        public Builder(String host, String searchUserBaseDN, String searchGroupBaseDN) {
            this.host = host;
            this.searchUserBaseDN = searchUserBaseDN;
            this.searchGroupBaseDN = searchGroupBaseDN;
        }

        public Builder setPort(int port) {
            this.port = port;
            return this;
        }

        public Builder setBindDnPassword(BindDnPassword bindDnPassword) {
            this.bindDnPassword = Optional.ofNullable(bindDnPassword);
            return this;
        }

        public Builder setPoolSize(int poolSize) {
            this.poolSize = poolSize;
            return this;
        }

        public Builder setConnectionTimeout(Duration connectionTimeout) {
            this.connectionTimeout = connectionTimeout;
            return this;
        }

        public Builder setRequestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder setSslEnabled(boolean sslEnabled) {
            this.sslEnabled = sslEnabled;
            return this;
        }

        public Builder setTrustAllCerts(boolean trustAllCerts) {
            this.trustAllCerts = trustAllCerts;
            return this;
        }

        public UnboundidLdapClient build() {
            return new UnboundidLdapClient(host, port, bindDnPassword, searchUserBaseDN, searchGroupBaseDN,
                    poolSize, connectionTimeout, requestTimeout, sslEnabled, trustAllCerts);
        }
    }
}

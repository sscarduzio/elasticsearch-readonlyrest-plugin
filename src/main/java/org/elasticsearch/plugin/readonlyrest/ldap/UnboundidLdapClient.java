package org.elasticsearch.plugin.readonlyrest.ldap;

import com.google.common.collect.Lists;
import com.unboundid.ldap.sdk.*;
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

    private final String searchUserBaseDN;
    private final String searchGroupBaseDN;
    private final LDAPConnectionPool connectionPool;
    private final Long timeout;

    public UnboundidLdapClient(String host,
                               int port,
                               String searchUserBaseDN,
                               String searchGroupBaseDN,
                               int poolSize,
                               Duration timeout) {
        this.searchUserBaseDN = searchUserBaseDN;
        this.searchGroupBaseDN = searchGroupBaseDN;
        this.timeout = timeout.toMillis();

        try {
            SSLUtil sslUtil = new SSLUtil(new TrustAllTrustManager());
            SSLSocketFactory sslSocketFactory = sslUtil.createSSLSocketFactory();
            LDAPConnection connection = new LDAPConnection(sslSocketFactory);
            connection.connect(host, port);
            connectionPool = new LDAPConnectionPool(connection, poolSize);
        } catch (GeneralSecurityException e) {
            throw new LdapClientInitializationException("SSL Factory creation problem", e);
        } catch (LDAPException e) {
            throw new LdapClientInitializationException("LDAP connection problem", e);
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
                        if (t instanceof LdapSearchError) {
                            LdapSearchError error = (LdapSearchError) t;
                            logger.debug(String.format("LDAP getting user CN returned error [%s]", error.getResultString()));
                        }
                        return Optional.empty();
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
                            String.format("(&(cn=*)(uniqueMember=%s))", user.getCn())
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
        try {
            BindResult result = connectionPool.bind(new SimpleBindRequest(user.getCn(), password));
            return ResultCode.SUCCESS.equals(result.getResultCode());
        } catch (LDAPException e) {
            logger.error("LDAP authenticate operation failed");
            return false;
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
        public void searchReferenceReturned(SearchResultReference searchReference) {}
    }

}

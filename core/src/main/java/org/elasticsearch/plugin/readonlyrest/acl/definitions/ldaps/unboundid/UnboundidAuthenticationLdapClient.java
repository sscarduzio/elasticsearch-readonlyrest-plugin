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
package org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.unboundid;

import com.unboundid.ldap.sdk.BindResult;
import com.unboundid.ldap.sdk.LDAPConnection;
import com.unboundid.ldap.sdk.LDAPException;
import com.unboundid.ldap.sdk.ResultCode;
import com.unboundid.ldap.sdk.SimpleBindRequest;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.LoggerShim;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.AuthenticationLdapClient;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.LdapCredentials;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.LdapUser;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class UnboundidAuthenticationLdapClient extends UnboundidBaseLdapClient implements AuthenticationLdapClient {

  private final LoggerShim logger;

  public UnboundidAuthenticationLdapClient(ConnectionConfig connectionConfig,
                                           UserSearchFilterConfig userSearchFilterConfig,
                                           Optional<SearchingUserConfig> searchingUserConfig,
                                           ESContext context) {
    super(new UnboundidConnection(connectionConfig, searchingUserConfig),
          connectionConfig.getRequestTimeout(), userSearchFilterConfig, context
    );
    this.logger = context.logger(getClass());
  }

  public UnboundidAuthenticationLdapClient(UnboundidConnection connection,
                                           Duration requestTimeout,
                                           UserSearchFilterConfig userSearchFilterConfig,
                                           ESContext context) {
    super(connection, requestTimeout, userSearchFilterConfig, context);
    this.logger = context.logger(getClass());
  }

  @Override
  public CompletableFuture<Optional<LdapUser>> authenticate(LdapCredentials credentials) {
    return userById(credentials.getUserName())
      .thenApply(user ->
                   user.map(u -> authenticate(u, credentials.getPassword()))
                     .flatMap(isAuthenticated -> isAuthenticated ? user : Optional.empty())
      );
  }

  private Boolean authenticate(LdapUser user, String password) {
    LDAPConnection ldapConnection = null;
    try {
      ldapConnection = connection.getConnectionPool().getConnection();
      BindResult result = ldapConnection.bind(new SimpleBindRequest(user.getDN(), password));
      return ResultCode.SUCCESS.equals(result.getResultCode());
    } catch (LDAPException e) {
      logger.error("LDAP authenticate operation failed: " + e.getMessage());
      return false;
    } finally {
      if (ldapConnection != null) {
        connection.getConnectionPool().releaseAndReAuthenticateConnection(ldapConnection);
      }
    }
  }
}

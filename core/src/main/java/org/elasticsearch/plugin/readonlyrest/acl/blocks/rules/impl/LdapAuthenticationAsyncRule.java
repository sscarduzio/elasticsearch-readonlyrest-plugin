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
package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl;

import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncAuthentication;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.AuthenticationLdapClient;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.LdapClientFactory;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.LdapCredentials;
import org.elasticsearch.plugin.readonlyrest.settings.rules.LdapAuthenticationRuleSettings;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class LdapAuthenticationAsyncRule extends AsyncAuthentication {

  private final AuthenticationLdapClient client;
  private final LdapAuthenticationRuleSettings settings;

  public LdapAuthenticationAsyncRule(LdapAuthenticationRuleSettings settings,
                                     LdapClientFactory factory,
                                     ESContext context) {
    super(context);
    this.settings = settings;
    this.client = factory.getClient(settings.getLdapSettings());
  }

  @Override
  protected CompletableFuture<Boolean> authenticate(String user, String password) {
    return client
      .authenticate(new LdapCredentials(user, password))
      .thenApply(Optional::isPresent);
  }

  @Override
  public String getKey() {
    return settings.getName();
  }

}

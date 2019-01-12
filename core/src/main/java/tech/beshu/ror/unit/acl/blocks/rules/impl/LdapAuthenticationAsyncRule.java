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
package tech.beshu.ror.unit.acl.blocks.rules.impl;

import tech.beshu.ror.unit.acl.blocks.rules.AsyncAuthentication;
import tech.beshu.ror.unit.acl.definitions.ldaps.AuthenticationLdapClient;
import tech.beshu.ror.unit.acl.definitions.ldaps.LdapClientFactory;
import tech.beshu.ror.unit.acl.definitions.ldaps.LdapCredentials;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.settings.rules.LdapAuthenticationRuleSettings;

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

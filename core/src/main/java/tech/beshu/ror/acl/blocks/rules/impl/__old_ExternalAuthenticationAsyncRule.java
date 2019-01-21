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
package tech.beshu.ror.acl.blocks.rules.impl;

import tech.beshu.ror.acl.blocks.rules.AsyncAuthentication;
import tech.beshu.ror.acl.definitions.externalauthenticationservices.ExternalAuthenticationServiceClient;
import tech.beshu.ror.acl.definitions.externalauthenticationservices.ExternalAuthenticationServiceClientFactory;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.settings.rules.__old_ExternalAuthenticationRuleSettings;

import java.util.concurrent.CompletableFuture;

public class __old_ExternalAuthenticationAsyncRule extends AsyncAuthentication {

  private final __old_ExternalAuthenticationRuleSettings settings;
  private final ExternalAuthenticationServiceClient client;

  public __old_ExternalAuthenticationAsyncRule(__old_ExternalAuthenticationRuleSettings settings,
                                         ExternalAuthenticationServiceClientFactory factory,
                                         ESContext context) {
    super(context);
    this.settings = settings;
    this.client = factory.getClient(settings.getExternalAuthenticationServiceSettings());
  }

  @Override
  protected CompletableFuture<Boolean> authenticate(String user, String password) {
    return client.authenticate(user, password);
  }

  @Override
  public String getKey() {
    return settings.getName();
  }
}

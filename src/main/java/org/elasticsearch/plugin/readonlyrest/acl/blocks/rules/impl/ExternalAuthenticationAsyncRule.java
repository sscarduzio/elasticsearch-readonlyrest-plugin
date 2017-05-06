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
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.BasicAsyncAuthentication;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.externalauthenticationservices.ExternalAuthenticationServiceClient;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.externalauthenticationservices.ExternalAuthenticationServiceClientFactory;
import org.elasticsearch.plugin.readonlyrest.settings.rules.ExternalAuthenticationRuleSettings;

import java.util.concurrent.CompletableFuture;

public class ExternalAuthenticationAsyncRule extends BasicAsyncAuthentication {

  private final ExternalAuthenticationRuleSettings settings;
  private final ExternalAuthenticationServiceClient client;

  public ExternalAuthenticationAsyncRule(ExternalAuthenticationRuleSettings settings,
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

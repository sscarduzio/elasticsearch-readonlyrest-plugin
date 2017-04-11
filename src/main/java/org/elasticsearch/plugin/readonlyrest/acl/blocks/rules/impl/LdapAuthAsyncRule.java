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

import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugin.readonlyrest.acl.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncAuthorization;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.BasicAsyncAuthentication;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.phantomtypes.Authentication;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.phantomtypes.Authorization;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.CachedAsyncAuthenticationDecorator.wrapInCacheIfCacheIsEnabled;
import static org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.CachedAsyncAuthorizationDecorator.wrapInCacheIfCacheIsEnabled;

public class LdapAuthAsyncRule extends AsyncRule implements Authentication, Authorization {

  private static final String RULE_NAME = "ldap_auth";

  private final BasicAsyncAuthentication authentication;
  private final AsyncAuthorization authorization;

  private LdapAuthAsyncRule(BasicAsyncAuthentication authentication, AsyncAuthorization authorization) {
    this.authentication = authentication;
    this.authorization = authorization;
  }

  public static Optional<LdapAuthAsyncRule> fromSettings(Settings s,
                                                         List<LdapConfig> ldapConfigs) throws ConfigMalformedException {
    return LdapAuthorizationAsyncRule.fromSettings(RULE_NAME, s, ldapConfigs)
        .map(authorization ->  new LdapAuthAsyncRule(
            wrapInCacheIfCacheIsEnabled(new LdapAuthenticationAsyncRule(authorization.getClient()), s),
            wrapInCacheIfCacheIsEnabled(authorization, s)
        ));
  }

  @Override
  public CompletableFuture<RuleExitResult> match(RequestContext rc) {
    return authentication.match(rc)
        .thenCompose(result -> result.isMatch()
            ? authorization.match(rc)
            : CompletableFuture.completedFuture(result)
        );
  }

  @Override
  public String getKey() {
    return RULE_NAME;
  }

}

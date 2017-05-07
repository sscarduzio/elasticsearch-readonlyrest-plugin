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
import org.elasticsearch.plugin.readonlyrest.requestcontext.RequestContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncAuthorization;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.BasicAsyncAuthentication;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.RuleExitResult;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.phantomtypes.Authentication;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.phantomtypes.Authorization;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.ldaps.LdapClientFactory;
import org.elasticsearch.plugin.readonlyrest.settings.rules.LdapAuthRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.LdapAuthenticationRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.LdapAuthorizationRuleSettings;

import java.util.concurrent.CompletableFuture;

import static org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.CachedAsyncAuthenticationDecorator.wrapInCacheIfCacheIsEnabled;
import static org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.CachedAsyncAuthorizationDecorator.wrapInCacheIfCacheIsEnabled;

public class LdapAuthAsyncRule extends AsyncRule implements Authentication, Authorization {

  private static final String RULE_NAME = "ldap_auth";

  private final BasicAsyncAuthentication authentication;
  private final AsyncAuthorization authorization;

  public LdapAuthAsyncRule(LdapAuthRuleSettings settings, LdapClientFactory factory, ESContext context) {
    LdapAuthenticationRuleSettings ldapAuthenticationRuleSettings = LdapAuthenticationRuleSettings.from(settings);
    this.authentication = wrapInCacheIfCacheIsEnabled(
        new LdapAuthenticationAsyncRule(ldapAuthenticationRuleSettings, factory, context),
        ldapAuthenticationRuleSettings,
        context
    );
    LdapAuthorizationRuleSettings ldapAuthorizationRuleSettings = LdapAuthorizationRuleSettings.from(settings);
    this.authorization = wrapInCacheIfCacheIsEnabled(
        new LdapAuthorizationAsyncRule(ldapAuthorizationRuleSettings, factory, context),
        ldapAuthorizationRuleSettings,
        context
    );
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

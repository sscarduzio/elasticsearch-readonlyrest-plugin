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

import tech.beshu.ror.commons.shims.ESContext;
import tech.beshu.ror.acl.blocks.rules.AsyncAuthentication;
import tech.beshu.ror.acl.blocks.rules.AsyncAuthorization;
import tech.beshu.ror.acl.blocks.rules.AsyncRule;
import tech.beshu.ror.acl.blocks.rules.RuleExitResult;
import tech.beshu.ror.acl.blocks.rules.phantomtypes.Authentication;
import tech.beshu.ror.acl.blocks.rules.phantomtypes.Authorization;
import tech.beshu.ror.acl.definitions.ldaps.LdapClientFactory;
import tech.beshu.ror.requestcontext.RequestContext;
import tech.beshu.ror.settings.rules.LdapAuthRuleSettings;
import tech.beshu.ror.settings.rules.LdapAuthenticationRuleSettings;
import tech.beshu.ror.settings.rules.LdapAuthorizationRuleSettings;
import tech.beshu.ror.acl.blocks.rules.CachedAsyncAuthenticationDecorator;
import tech.beshu.ror.acl.blocks.rules.CachedAsyncAuthorizationDecorator;

import java.util.concurrent.CompletableFuture;

public class LdapAuthAsyncRule extends AsyncRule implements Authentication, Authorization {

  private static final String RULE_NAME = "ldap_auth";

  private final AsyncAuthentication authentication;
  private final AsyncAuthorization authorization;

  public LdapAuthAsyncRule(LdapAuthRuleSettings settings, LdapClientFactory factory, ESContext context) {
    LdapAuthenticationRuleSettings ldapAuthenticationRuleSettings = LdapAuthenticationRuleSettings.from(settings);
    this.authentication = CachedAsyncAuthenticationDecorator.wrapInCacheIfCacheIsEnabled(
      new LdapAuthenticationAsyncRule(ldapAuthenticationRuleSettings, factory, context),
      ldapAuthenticationRuleSettings,
      context
    );
    LdapAuthorizationRuleSettings ldapAuthorizationRuleSettings = LdapAuthorizationRuleSettings.from(settings);
    this.authorization = CachedAsyncAuthorizationDecorator.wrapInCacheIfCacheIsEnabled(
      new LdapAuthorizationAsyncRule(ldapAuthorizationRuleSettings, factory, context),
      ldapAuthorizationRuleSettings,
      context
    );
  }

  @Override
  public CompletableFuture<RuleExitResult> match(RequestContext rc) {
    return authentication.match(rc)
      .exceptionally(e -> {
        e.printStackTrace();
        return NO_MATCH;
      })
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

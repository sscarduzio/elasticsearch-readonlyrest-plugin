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
package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

import com.google.common.collect.ImmutableList;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ActionsSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ApiKeysSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeySha1SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeySha256SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeySyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ExternalAuthenticationAsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.GroupsProviderAuthorizationAsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.GroupsSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.HostsSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.IndicesRewriteSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.IndicesSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.KibanaAccessSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapAuthAsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapAuthenticationAsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapAuthorizationAsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.MaxBodyLengthSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.MethodsSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ProxyAuthSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.SearchlogSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.SessionMaxIdleSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.UriReSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.XForwardedForSyncRule;
import org.elasticsearch.plugin.readonlyrest.utils.RulesUtils;

import java.util.Comparator;

public class RulesOrdering implements Comparator<AsyncRule> {

  private final ImmutableList<Class<? extends Rule>> ordering;

  public RulesOrdering() {
    this.ordering = ImmutableList.of(
        // Authentication rules must come first because they set the user information which further rules might rely on.
        AuthKeySyncRule.class,
        AuthKeySha1SyncRule.class,
        AuthKeySha256SyncRule.class,
        ProxyAuthSyncRule.class,
        // Inspection rules next; these act based on properties of the request.
        KibanaAccessSyncRule.class,
        HostsSyncRule.class,
        XForwardedForSyncRule.class,
        ApiKeysSyncRule.class,
        SessionMaxIdleSyncRule.class,
        UriReSyncRule.class,
        MaxBodyLengthSyncRule.class,
        MethodsSyncRule.class,
        IndicesSyncRule.class,
        ActionsSyncRule.class,
        GroupsSyncRule.class,
        SearchlogSyncRule.class,
        // then we could check potentially slow async rules
        LdapAuthAsyncRule.class,
        LdapAuthenticationAsyncRule.class,
        ExternalAuthenticationAsyncRule.class,
        // all authorization rules should be placed before any authentication rule
        LdapAuthorizationAsyncRule.class,
        GroupsProviderAuthorizationAsyncRule.class,
        // At the end the sync rule chain are those that can mutate the client request.
        IndicesRewriteSyncRule.class
    );
  }

  @Override
  public int compare(AsyncRule r1, AsyncRule r2) {
    return Integer.compare(
        indexOfRuleClass(RulesUtils.classOfRule(r1)),
        indexOfRuleClass(RulesUtils.classOfRule(r2))
    );
  }

  private int indexOfRuleClass(Class<? extends Rule> ruleClass) {
    int index = ordering.indexOf(ruleClass);
    if (index < 0)
      throw new IllegalStateException("Cannot find class '" + ruleClass.getName() + "' in rules ordering " +
          "list in '" + RulesOrdering.class.getName() + "' class");
    return index;
  }
}

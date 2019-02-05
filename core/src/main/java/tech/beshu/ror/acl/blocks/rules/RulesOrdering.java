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

package tech.beshu.ror.acl.blocks.rules;

import com.google.common.collect.ImmutableList;
import tech.beshu.ror.acl.blocks.rules.impl.ActionsSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.ApiKeysSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.AuthKeySha1SyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.AuthKeySha256SyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.AuthKeySha512SyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.AuthKeySyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.AuthKeyUnixAsyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.ExternalAuthenticationAsyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.FieldsSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.FilterSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.GroupsAsyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.GroupsProviderAuthorizationAsyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.HeadersAndSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.HeadersOrSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.HeadersSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.HostsSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.IndicesSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.JwtAuthAsyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.KibanaAccessSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.KibanaHideAppsSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.KibanaIndexSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.LdapAuthAsyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.LdapAuthenticationAsyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.LdapAuthorizationAsyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.LocalHostsSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.MaxBodyLengthSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.MethodsSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.ProxyAuthSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.RepositoriesSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.RorKbnAuthSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.SearchlogSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.SessionMaxIdleSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.SnapshotsSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.UriReSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.UsersSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.XForwardedForSyncRule;
import tech.beshu.ror.utils.RulesUtils;

import java.util.Comparator;

public class RulesOrdering implements Comparator<AsyncRule> {

  private final ImmutableList<Class<? extends Rule>> ordering;

  public RulesOrdering() {
    this.ordering = ImmutableList.of(

        // Authentication rules must come first because they set the user information which further rules might rely on.
        AuthKeySyncRule.class,
        AuthKeySha1SyncRule.class,
        AuthKeySha256SyncRule.class,
        AuthKeySha512SyncRule.class,
        AuthKeyUnixAsyncRule.class,
        ProxyAuthSyncRule.class,
        JwtAuthAsyncRule.class,
        RorKbnAuthSyncRule.class,

        // then we could check potentially slow async rules
        LdapAuthAsyncRule.class,
        LdapAuthenticationAsyncRule.class,
        ExternalAuthenticationAsyncRule.class,
        GroupsAsyncRule.class,

        // Inspection rules next; these act based on properties of the request.
        KibanaAccessSyncRule.class,

        HostsSyncRule.class,
        LocalHostsSyncRule.class,
        SnapshotsSyncRule.class,
        RepositoriesSyncRule.class,
        XForwardedForSyncRule.class,
        ApiKeysSyncRule.class,
        SessionMaxIdleSyncRule.class,
        UriReSyncRule.class,
        MaxBodyLengthSyncRule.class,
        MethodsSyncRule.class,
        HeadersSyncRule.class,
        HeadersAndSyncRule.class,
        HeadersOrSyncRule.class,
        IndicesSyncRule.class,
        ActionsSyncRule.class,
        SearchlogSyncRule.class,
        UsersSyncRule.class,

        // all authorization rules should be placed before any authentication rule
        LdapAuthorizationAsyncRule.class,
        GroupsProviderAuthorizationAsyncRule.class,

        // At the end the sync rule chain are those that can mutate the client request.
        KibanaHideAppsSyncRule.class,
        KibanaIndexSyncRule.class,

        // Stuff to do later, at search time
        FieldsSyncRule.class,
        FilterSyncRule.class
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

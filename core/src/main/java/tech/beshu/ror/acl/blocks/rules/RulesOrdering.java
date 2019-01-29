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
import tech.beshu.ror.acl.blocks.rules.impl.__old_JwtAuthAsyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_ActionsSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_ApiKeysSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_AuthKeySha1SyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_AuthKeySha256SyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_AuthKeySha512SyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_AuthKeySyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_AuthKeyUnixAsyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_ExternalAuthenticationAsyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_FieldsSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_FilterSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_GroupsAsyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_GroupsProviderAuthorizationAsyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_HeadersAndSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_HeadersOrSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_HeadersSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_HostsSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_IndicesSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_KibanaAccessSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_KibanaHideAppsSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_KibanaIndexSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.LdapAuthAsyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.LdapAuthenticationAsyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.LdapAuthorizationAsyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_LocalHostsSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_MaxBodyLengthSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_MethodsSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_ProxyAuthSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_RepositoriesSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.RorKbnAuthSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_SearchlogSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_SessionMaxIdleSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_SnapshotsSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_UriReSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_UsersSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_XForwardedForSyncRule;
import tech.beshu.ror.utils.RulesUtils;

import java.util.Comparator;

public class RulesOrdering implements Comparator<AsyncRule> {

  private final ImmutableList<Class<? extends __old_Rule>> ordering;

  public RulesOrdering() {
    this.ordering = ImmutableList.of(

        // Authentication rules must come first because they set the user information which further rules might rely on.
        __old_AuthKeySyncRule.class,
        __old_AuthKeySha1SyncRule.class,
        __old_AuthKeySha256SyncRule.class,
        __old_AuthKeySha512SyncRule.class,
        __old_AuthKeyUnixAsyncRule.class,
        __old_ProxyAuthSyncRule.class,
        __old_JwtAuthAsyncRule.class,
        RorKbnAuthSyncRule.class,

        // then we could check potentially slow async rules
        LdapAuthAsyncRule.class,
        LdapAuthenticationAsyncRule.class,
        __old_ExternalAuthenticationAsyncRule.class,
        __old_GroupsAsyncRule.class,

        // Inspection rules next; these act based on properties of the request.
        __old_KibanaAccessSyncRule.class,

        __old_HostsSyncRule.class,
        __old_LocalHostsSyncRule.class,
        __old_SnapshotsSyncRule.class,
        __old_RepositoriesSyncRule.class,
        __old_XForwardedForSyncRule.class,
        __old_ApiKeysSyncRule.class,
        __old_SessionMaxIdleSyncRule.class,
        __old_UriReSyncRule.class,
        __old_MaxBodyLengthSyncRule.class,
        __old_MethodsSyncRule.class,
        __old_HeadersSyncRule.class,
        __old_HeadersAndSyncRule.class,
        __old_HeadersOrSyncRule.class,
        __old_IndicesSyncRule.class,
        __old_ActionsSyncRule.class,
        __old_SearchlogSyncRule.class,
        __old_UsersSyncRule.class,

        // all authorization rules should be placed before any authentication rule
        LdapAuthorizationAsyncRule.class,
        __old_GroupsProviderAuthorizationAsyncRule.class,

        // At the end the sync rule chain are those that can mutate the client request.
        __old_KibanaHideAppsSyncRule.class,
        __old_KibanaIndexSyncRule.class,

        // Stuff to do later, at search time
        __old_FieldsSyncRule.class,
        __old_FilterSyncRule.class
        );
  }

  @Override
  public int compare(AsyncRule r1, AsyncRule r2) {
    return Integer.compare(
        indexOfRuleClass(RulesUtils.classOfRule(r1)),
        indexOfRuleClass(RulesUtils.classOfRule(r2))
    );
  }

  private int indexOfRuleClass(Class<? extends __old_Rule> ruleClass) {
    int index = ordering.indexOf(ruleClass);
    if (index < 0)
      throw new IllegalStateException("Cannot find class '" + ruleClass.getName() + "' in rules ordering " +
          "list in '" + RulesOrdering.class.getName() + "' class");
    return index;
  }
}

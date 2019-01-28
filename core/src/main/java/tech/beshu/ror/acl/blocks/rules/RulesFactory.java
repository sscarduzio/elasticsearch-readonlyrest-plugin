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

import com.google.common.collect.Maps;
import tech.beshu.ror.acl.blocks.rules.impl.__old_ActionsSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_ApiKeysSyncRule;
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
import tech.beshu.ror.acl.blocks.rules.impl.__old_RepositoriesSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_SearchlogSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_SessionMaxIdleSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_SnapshotsSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_UriReSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_UsersSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_XForwardedForSyncRule;
import tech.beshu.ror.acl.definitions.DefinitionsFactory;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.settings.AuthKeyProviderSettings;
import tech.beshu.ror.settings.RuleSettings;
import tech.beshu.ror.settings.rules.__old_ActionsRuleSettings;
import tech.beshu.ror.settings.rules.__old_ApiKeysRuleSettings;
import tech.beshu.ror.settings.rules.__old_ExternalAuthenticationRuleSettings;
import tech.beshu.ror.settings.rules.__old_GroupsProviderAuthorizationRuleSettings;
import tech.beshu.ror.settings.rules.__old_GroupsRuleSettings;
import tech.beshu.ror.settings.rules.__old_HostsRuleSettings;
import tech.beshu.ror.settings.rules.__old_IndicesRuleSettings;
import tech.beshu.ror.settings.rules.__old_KibanaAccessRuleSettings;
import tech.beshu.ror.settings.rules.__old_KibanaHideAppsRuleSettings;
import tech.beshu.ror.settings.rules.LdapAuthRuleSettings;
import tech.beshu.ror.settings.rules.LdapAuthenticationRuleSettings;
import tech.beshu.ror.settings.rules.LdapAuthorizationRuleSettings;
import tech.beshu.ror.settings.rules.__old_LocalHostsRuleSettings;
import tech.beshu.ror.settings.rules.__old_MaxBodyLengthRuleSettings;
import tech.beshu.ror.settings.rules.__old_MethodsRuleSettings;
import tech.beshu.ror.settings.rules.SearchlogRuleSettings;
import tech.beshu.ror.settings.rules.__old_SessionMaxIdleRuleSettings;
import tech.beshu.ror.settings.rules.__old_XForwardedForRuleSettings;

import java.util.Map;
import java.util.function.Function;

public class RulesFactory {

  private final Map<Class<? extends RuleSettings>, Function<RuleSettings, AsyncRule>> creators;
  private final UserRuleFactory userRuleFactory;
  private final ESContext context;

  public RulesFactory(DefinitionsFactory definitionsFactory, UserRuleFactory userRuleFactory, ESContext context) {
    this.userRuleFactory = userRuleFactory;
    this.context = context;
    this.creators = Maps.newHashMap();
    this.creators.put(
        __old_ActionsRuleSettings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_ActionsSyncRule((__old_ActionsRuleSettings) settings, context))
    );
    this.creators.put(
        __old_ApiKeysRuleSettings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_ApiKeysSyncRule((__old_ApiKeysRuleSettings) settings))
    );
    this.creators.put(
        __old_HostsRuleSettings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_HostsSyncRule((__old_HostsRuleSettings) settings, context))
    );
    this.creators.put(
        __old_LocalHostsRuleSettings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_LocalHostsSyncRule((__old_LocalHostsRuleSettings) settings, context))
    );
    this.creators.put(
        __old_SnapshotsSyncRule.Settings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_SnapshotsSyncRule((__old_SnapshotsSyncRule.Settings) settings, context))
    );
    this.creators.put(
        __old_RepositoriesSyncRule.Settings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_RepositoriesSyncRule((__old_RepositoriesSyncRule.Settings) settings, context))
    );
    this.creators.put(
        __old_IndicesRuleSettings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_IndicesSyncRule((__old_IndicesRuleSettings) settings, context))
    );
    this.creators.put(
        __old_KibanaAccessRuleSettings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_KibanaAccessSyncRule((__old_KibanaAccessRuleSettings) settings, context))
    );
    this.creators.put(
        __old_KibanaIndexSyncRule.Settings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_KibanaIndexSyncRule((__old_KibanaIndexSyncRule.Settings) settings))
    );
    this.creators.put(
        __old_FieldsSyncRule.Settings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_FieldsSyncRule((__old_FieldsSyncRule.Settings) settings))
    );
    this.creators.put(
        __old_FilterSyncRule.Settings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_FilterSyncRule((__old_FilterSyncRule.Settings) settings))
    );
    this.creators.put(
        __old_KibanaHideAppsRuleSettings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_KibanaHideAppsSyncRule((__old_KibanaHideAppsRuleSettings) settings, context))
    );
    this.creators.put(
        __old_MaxBodyLengthRuleSettings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_MaxBodyLengthSyncRule((__old_MaxBodyLengthRuleSettings) settings))
    );
    this.creators.put(
        __old_MethodsRuleSettings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_MethodsSyncRule((__old_MethodsRuleSettings) settings))
    );
    this.creators.put(
        __old_HeadersSyncRule.Settings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_HeadersSyncRule((__old_HeadersSyncRule.Settings) settings))
    );
    this.creators.put(
        __old_HeadersAndSyncRule.Settings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_HeadersAndSyncRule((__old_HeadersAndSyncRule.Settings) settings))
    );
    this.creators.put(
        __old_HeadersOrSyncRule.Settings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_HeadersOrSyncRule((__old_HeadersOrSyncRule.Settings) settings))
    );
    this.creators.put(
        SearchlogRuleSettings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_SearchlogSyncRule((SearchlogRuleSettings) settings))
    );
    this.creators.put(
        __old_SessionMaxIdleRuleSettings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_SessionMaxIdleSyncRule((__old_SessionMaxIdleRuleSettings) settings, context))
    );
    this.creators.put(
        __old_UriReSyncRule.Settings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_UriReSyncRule((__old_UriReSyncRule.Settings) settings))
    );
    this.creators.put(
        __old_UsersSyncRule.Settings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_UsersSyncRule((__old_UsersSyncRule.Settings) settings))
    );
    this.creators.put(
        __old_XForwardedForRuleSettings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_XForwardedForSyncRule((__old_XForwardedForRuleSettings) settings, context))
    );
    this.creators.put(
        LdapAuthenticationRuleSettings.class,
        settings -> CachedAsyncAuthenticationDecorator.wrapInCacheIfCacheIsEnabled(
            new LdapAuthenticationAsyncRule((LdapAuthenticationRuleSettings) settings, definitionsFactory, context),
            (LdapAuthenticationRuleSettings) settings,
            context
        )
    );
    this.creators.put(
        LdapAuthorizationRuleSettings.class,
        settings -> CachedAsyncAuthorizationDecorator.wrapInCacheIfCacheIsEnabled(
            new LdapAuthorizationAsyncRule((LdapAuthorizationRuleSettings) settings, definitionsFactory, context),
            (LdapAuthorizationRuleSettings) settings,
            context
        )
    );
    this.creators.put(
        LdapAuthRuleSettings.class,
        settings -> new LdapAuthAsyncRule((LdapAuthRuleSettings) settings, definitionsFactory, context)
    );
    this.creators.put(
        __old_ExternalAuthenticationRuleSettings.class,
        settings -> CachedAsyncAuthenticationDecorator.wrapInCacheIfCacheIsEnabled(
            new __old_ExternalAuthenticationAsyncRule((__old_ExternalAuthenticationRuleSettings) settings, definitionsFactory, context),
            (__old_ExternalAuthenticationRuleSettings) settings,
            context
        )
    );
    this.creators.put(
        __old_GroupsProviderAuthorizationRuleSettings.class,
        settings -> CachedAsyncAuthorizationDecorator.wrapInCacheIfCacheIsEnabled(
            new __old_GroupsProviderAuthorizationAsyncRule((__old_GroupsProviderAuthorizationRuleSettings) settings, definitionsFactory, context),
            (__old_GroupsProviderAuthorizationRuleSettings) settings,
            context
        )
    );
    this.creators.put(
        __old_GroupsRuleSettings.class,
        settings ->
            new __old_GroupsAsyncRule((__old_GroupsRuleSettings) settings, definitionsFactory)
    );
  }

  public AsyncRule create(RuleSettings settings) {
    Class<? extends RuleSettings> ruleSettingsClass = settings.getClass();
    if (settings instanceof AuthKeyProviderSettings) {
      return userRuleFactory.create((AuthKeyProviderSettings) settings);
    }
    else if (creators.containsKey(ruleSettingsClass)) {
      return creators.get(settings.getClass()).apply(settings);
    }
    else {
      throw context.rorException("Cannot find rule for config class [" + ruleSettingsClass.getName() + "]");
    }
  }
}

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
import tech.beshu.ror.acl.blocks.rules.impl.ActionsSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.ApiKeysSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.ExternalAuthenticationAsyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.GroupsAsyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.GroupsProviderAuthorizationAsyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.HostsSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.IndicesSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.KibanaAccessSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.KibanaHideAppsSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.KibanaIndexSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.LdapAuthAsyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.LdapAuthenticationAsyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.LdapAuthorizationAsyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.MaxBodyLengthSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.MethodsSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.SearchlogSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.SessionMaxIdleSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.UriReSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.XForwardedForSyncRule;
import tech.beshu.ror.acl.definitions.DefinitionsFactory;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.settings.AuthKeyProviderSettings;
import tech.beshu.ror.settings.RuleSettings;
import tech.beshu.ror.settings.rules.ActionsRuleSettings;
import tech.beshu.ror.settings.rules.ApiKeysRuleSettings;
import tech.beshu.ror.settings.rules.ExternalAuthenticationRuleSettings;
import tech.beshu.ror.settings.rules.GroupsProviderAuthorizationRuleSettings;
import tech.beshu.ror.settings.rules.GroupsRuleSettings;
import tech.beshu.ror.settings.rules.HostsRuleSettings;
import tech.beshu.ror.settings.rules.IndicesRuleSettings;
import tech.beshu.ror.settings.rules.KibanaAccessRuleSettings;
import tech.beshu.ror.settings.rules.KibanaHideAppsRuleSettings;
import tech.beshu.ror.settings.rules.LdapAuthRuleSettings;
import tech.beshu.ror.settings.rules.LdapAuthenticationRuleSettings;
import tech.beshu.ror.settings.rules.LdapAuthorizationRuleSettings;
import tech.beshu.ror.settings.rules.MaxBodyLengthRuleSettings;
import tech.beshu.ror.settings.rules.MethodsRuleSettings;
import tech.beshu.ror.settings.rules.SearchlogRuleSettings;
import tech.beshu.ror.settings.rules.SessionMaxIdleRuleSettings;
import tech.beshu.ror.settings.rules.UriReRuleSettings;
import tech.beshu.ror.settings.rules.XForwardedForRuleSettings;

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
      ActionsRuleSettings.class,
      settings -> AsyncRuleAdapter.wrap(new ActionsSyncRule((ActionsRuleSettings) settings, context))
    );
    this.creators.put(
      ApiKeysRuleSettings.class,
      settings -> AsyncRuleAdapter.wrap(new ApiKeysSyncRule((ApiKeysRuleSettings) settings))
    );
    this.creators.put(
      HostsRuleSettings.class,
      settings -> AsyncRuleAdapter.wrap(new HostsSyncRule((HostsRuleSettings) settings, context))
    );
    this.creators.put(
      IndicesRuleSettings.class,
      settings -> AsyncRuleAdapter.wrap(new IndicesSyncRule((IndicesRuleSettings) settings, context))
    );
    this.creators.put(
      KibanaAccessRuleSettings.class,
      settings -> AsyncRuleAdapter.wrap(new KibanaAccessSyncRule((KibanaAccessRuleSettings) settings, context))
    );
    this.creators.put(
      KibanaIndexSyncRule.Settings.class,
      settings -> AsyncRuleAdapter.wrap(new KibanaIndexSyncRule((KibanaIndexSyncRule.Settings) settings))
    );
    this.creators.put(
      KibanaHideAppsRuleSettings.class,
      settings -> AsyncRuleAdapter.wrap(new KibanaHideAppsSyncRule((KibanaHideAppsRuleSettings) settings, context))
    );
    this.creators.put(
      MaxBodyLengthRuleSettings.class,
      settings -> AsyncRuleAdapter.wrap(new MaxBodyLengthSyncRule((MaxBodyLengthRuleSettings) settings))
    );
    this.creators.put(
      MethodsRuleSettings.class,
      settings -> AsyncRuleAdapter.wrap(new MethodsSyncRule((MethodsRuleSettings) settings))
    );
    this.creators.put(
      SearchlogRuleSettings.class,
      settings -> AsyncRuleAdapter.wrap(new SearchlogSyncRule((SearchlogRuleSettings) settings))
    );
    this.creators.put(
      SessionMaxIdleRuleSettings.class,
      settings -> AsyncRuleAdapter.wrap(new SessionMaxIdleSyncRule((SessionMaxIdleRuleSettings) settings, context))
    );
    this.creators.put(
      UriReRuleSettings.class,
      settings -> AsyncRuleAdapter.wrap(new UriReSyncRule((UriReRuleSettings) settings))
    );
    this.creators.put(
      XForwardedForRuleSettings.class,
      settings -> AsyncRuleAdapter.wrap(new XForwardedForSyncRule((XForwardedForRuleSettings) settings))
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
      ExternalAuthenticationRuleSettings.class,
      settings -> CachedAsyncAuthenticationDecorator.wrapInCacheIfCacheIsEnabled(
        new ExternalAuthenticationAsyncRule((ExternalAuthenticationRuleSettings) settings, definitionsFactory, context),
        (ExternalAuthenticationRuleSettings) settings,
        context
      )
    );
    this.creators.put(
      GroupsProviderAuthorizationRuleSettings.class,
      settings -> CachedAsyncAuthorizationDecorator.wrapInCacheIfCacheIsEnabled(
        new GroupsProviderAuthorizationAsyncRule((GroupsProviderAuthorizationRuleSettings) settings, definitionsFactory, context),
        (GroupsProviderAuthorizationRuleSettings) settings,
        context
      )
    );
    this.creators.put(
      GroupsRuleSettings.class,
      settings ->
        new GroupsAsyncRule((GroupsRuleSettings) settings, definitionsFactory)
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

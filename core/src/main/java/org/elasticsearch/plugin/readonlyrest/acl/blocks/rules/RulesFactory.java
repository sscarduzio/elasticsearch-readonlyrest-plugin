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

import com.google.common.collect.Maps;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ActionsSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ApiKeysSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ExternalAuthenticationAsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.GroupsProviderAuthorizationAsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.GroupsSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.HostsSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.IndicesRewriteSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.IndicesSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.KibanaAccessSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.KibanaHideAppsSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapAuthAsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapAuthenticationAsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.LdapAuthorizationAsyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.MaxBodyLengthSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.MethodsSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.SearchlogSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.SessionMaxIdleSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.UriReSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.XForwardedForSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.definitions.DefinitionsFactory;
import org.elasticsearch.plugin.readonlyrest.settings.AuthKeyProviderSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.ActionsRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.ApiKeysRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.ExternalAuthenticationRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.GroupsProviderAuthorizationRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.GroupsRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.HostsRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.IndicesRewriteRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.IndicesRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.KibanaAccessRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.KibanaHideAppsRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.LdapAuthRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.LdapAuthenticationRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.LdapAuthorizationRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.MaxBodyLengthRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.MethodsRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.SearchlogRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.SessionMaxIdleRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.UriReRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.XForwardedForRuleSettings;

import java.util.Map;
import java.util.function.Function;

import static org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncRuleAdapter.wrap;
import static org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.CachedAsyncAuthenticationDecorator.wrapInCacheIfCacheIsEnabled;
import static org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.CachedAsyncAuthorizationDecorator.wrapInCacheIfCacheIsEnabled;

public class RulesFactory {

  private final Map<Class<? extends RuleSettings>, Function<RuleSettings, AsyncRule>> creators;
  private final UserRuleFactory userRuleFactory;
  private final ESContext context;

  public RulesFactory(DefinitionsFactory definitionsFactory, UserRuleFactory userRuleFactory, ESContext context) {
    this.userRuleFactory = userRuleFactory;
    this.context = context;
    this.creators = Maps.newHashMap();
    this.creators.put(ActionsRuleSettings.class,
        settings -> wrap(new ActionsSyncRule((ActionsRuleSettings) settings, context)));
    this.creators.put(ApiKeysRuleSettings.class,
        settings -> wrap(new ApiKeysSyncRule((ApiKeysRuleSettings) settings)));
    this.creators.put(HostsRuleSettings.class,
        settings -> wrap(new HostsSyncRule((HostsRuleSettings) settings, context)));
    this.creators.put(IndicesRuleSettings.class,
        settings -> wrap(new IndicesSyncRule((IndicesRuleSettings) settings, context)));
    this.creators.put(IndicesRewriteRuleSettings.class,
        settings -> wrap(new IndicesRewriteSyncRule((IndicesRewriteRuleSettings) settings, context)));
    this.creators.put(KibanaAccessRuleSettings.class,
        settings -> wrap(new KibanaAccessSyncRule((KibanaAccessRuleSettings) settings, context)));
    this.creators.put(KibanaHideAppsRuleSettings.class,
        settings -> wrap(new KibanaHideAppsSyncRule((KibanaHideAppsRuleSettings) settings, context)));
    this.creators.put(MaxBodyLengthRuleSettings.class,
        settings -> wrap(new MaxBodyLengthSyncRule((MaxBodyLengthRuleSettings) settings)));
    this.creators.put(MethodsRuleSettings.class,
        settings -> wrap(new MethodsSyncRule((MethodsRuleSettings) settings)));
    this.creators.put(SearchlogRuleSettings.class,
        settings -> wrap(new SearchlogSyncRule((SearchlogRuleSettings) settings)));
    this.creators.put(SessionMaxIdleRuleSettings.class,
        settings -> wrap(new SessionMaxIdleSyncRule((SessionMaxIdleRuleSettings) settings, context)));
    this.creators.put(UriReRuleSettings.class,
        settings -> wrap(new UriReSyncRule((UriReRuleSettings) settings)));
    this.creators.put(XForwardedForRuleSettings.class,
        settings -> wrap(new XForwardedForSyncRule((XForwardedForRuleSettings) settings)));
    this.creators.put(LdapAuthenticationRuleSettings.class,
        settings -> wrapInCacheIfCacheIsEnabled(
            new LdapAuthenticationAsyncRule((LdapAuthenticationRuleSettings) settings, definitionsFactory, context),
            (LdapAuthenticationRuleSettings) settings,
            context)
    );
    this.creators.put(LdapAuthorizationRuleSettings.class,
        settings -> wrapInCacheIfCacheIsEnabled(
            new LdapAuthorizationAsyncRule((LdapAuthorizationRuleSettings) settings, definitionsFactory, context),
            (LdapAuthorizationRuleSettings) settings,
            context)
    );
    this.creators.put(LdapAuthRuleSettings.class,
        settings -> new LdapAuthAsyncRule((LdapAuthRuleSettings) settings, definitionsFactory, context));
    this.creators.put(ExternalAuthenticationRuleSettings.class,
        settings -> wrapInCacheIfCacheIsEnabled(
            new ExternalAuthenticationAsyncRule((ExternalAuthenticationRuleSettings) settings, definitionsFactory, context),
            (ExternalAuthenticationRuleSettings) settings,
            context
        )
    );
    this.creators.put(GroupsProviderAuthorizationRuleSettings.class,
        settings -> wrapInCacheIfCacheIsEnabled(
            new GroupsProviderAuthorizationAsyncRule((GroupsProviderAuthorizationRuleSettings) settings, definitionsFactory, context),
            (GroupsProviderAuthorizationRuleSettings) settings,
            context
        )
    );
    this.creators.put(GroupsRuleSettings.class,
        settings ->
            wrap(new GroupsSyncRule((GroupsRuleSettings) settings, definitionsFactory))
    );
  }

  public AsyncRule create(RuleSettings settings) {
    Class<? extends RuleSettings> ruleSettingsClass = settings.getClass();
    if (settings instanceof AuthKeyProviderSettings) {
      return wrap(userRuleFactory.create((AuthKeyProviderSettings) settings));
    } else if (creators.containsKey(ruleSettingsClass)) {
      return creators.get(settings.getClass()).apply(settings);
    } else {
      throw context.rorException("Cannot find rule for config class [" + ruleSettingsClass.getName() + "]");
    }
  }
}

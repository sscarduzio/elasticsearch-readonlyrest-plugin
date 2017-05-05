package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

import com.google.common.collect.Maps;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ActionsSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ApiKeysSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeySha1SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeySha256SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeySyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.HostsSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.IndicesRewriteSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.IndicesSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.KibanaAccessSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.KibanaHideAppsSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.MaxBodyLengthSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.MethodsSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.SearchlogSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.SessionMaxIdleSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.UriReSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.VerbositySyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.XForwardedForSyncRule;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.ActionsRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.ApiKeysRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.AuthKeyPlainTextRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.AuthKeySha1RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.AuthKeySha256RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.HostsRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.IndicesRewriteRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.IndicesRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.KibanaAccessRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.KibanaHideAppsRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.MaxBodyLengthRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.MethodsRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.SearchlogRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.SessionMaxIdleRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.UriReRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.VerbosityRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.XForwardedForRuleSettings;

import java.util.Map;
import java.util.function.Function;

import static org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.AsyncRuleAdapter.wrap;

public class RulesFactory {

  private final Map<Class<? extends RuleSettings>, Function<RuleSettings, AsyncRule>> creators;
  private final ESContext context;

  public RulesFactory(ESContext context) {
    this.context = context;
    this.creators = Maps.newHashMap();
    this.creators.put(ActionsRuleSettings.class,
        settings -> wrap(new ActionsSyncRule((ActionsRuleSettings) settings, context)));
    this.creators.put(ApiKeysRuleSettings.class,
        settings -> wrap(new ApiKeysSyncRule((ApiKeysRuleSettings) settings)));
    this.creators.put(AuthKeySha1RuleSettings.class,
        settings -> wrap(new AuthKeySha1SyncRule((AuthKeySha1RuleSettings) settings, context)));
    this.creators.put(AuthKeySha256RuleSettings.class,
        settings -> wrap(new AuthKeySha256SyncRule((AuthKeySha256RuleSettings) settings, context)));
    this.creators.put(AuthKeyPlainTextRuleSettings.class,
        settings -> wrap(new AuthKeySyncRule((AuthKeyPlainTextRuleSettings) settings, context)));
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
        settings -> wrap(new SearchlogSyncRule((SearchlogRuleSettings) settings, context)));
    this.creators.put(SessionMaxIdleRuleSettings.class,
        settings -> wrap(new SessionMaxIdleSyncRule((SessionMaxIdleRuleSettings) settings, context)));
    this.creators.put(UriReRuleSettings.class,
        settings -> wrap(new UriReSyncRule((UriReRuleSettings) settings)));
    this.creators.put(VerbosityRuleSettings.class,
        settings -> wrap(new VerbositySyncRule((VerbosityRuleSettings) settings)));
    this.creators.put(XForwardedForRuleSettings.class,
        settings -> wrap(new XForwardedForSyncRule((XForwardedForRuleSettings) settings)));
  }

  public AsyncRule create(RuleSettings settings) {
    Class<? extends RuleSettings> ruleSettingsClass = settings.getClass();
    if (creators.containsKey(ruleSettingsClass)) {
      return creators.get(settings.getClass()).apply(settings);
    } else {
      throw context.rorException("Cannot find rule for config class [" + ruleSettingsClass.getName() + "]");
    }
  }
}

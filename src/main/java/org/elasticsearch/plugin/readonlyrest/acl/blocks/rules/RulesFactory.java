package org.elasticsearch.plugin.readonlyrest.acl.blocks.rules;

import com.google.common.collect.Maps;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ActionsSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ApiKeysSyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeySha1SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeySha256SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeySyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.IndicesSyncRule;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.ActionsRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.ApiKeysRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.AuthKeyPlainTextRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.AuthKeySha1RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.AuthKeySha256RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.IndicesRuleSettings;

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
    this.creators.put(IndicesRuleSettings.class,
        settings -> wrap(new IndicesSyncRule((IndicesRuleSettings) settings, context)));
    this.creators.put(ApiKeysRuleSettings.class,
        settings -> wrap(new ApiKeysSyncRule((ApiKeysRuleSettings) settings)));
    this.creators.put(AuthKeySha1RuleSettings.class,
        settings -> wrap(new AuthKeySha1SyncRule((AuthKeySha1RuleSettings) settings, context)));
    this.creators.put(AuthKeySha256RuleSettings.class,
        settings -> wrap(new AuthKeySha256SyncRule((AuthKeySha256RuleSettings) settings, context)));
    this.creators.put(AuthKeyPlainTextRuleSettings.class,
        settings -> wrap(new AuthKeySyncRule((AuthKeyPlainTextRuleSettings) settings, context)));
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

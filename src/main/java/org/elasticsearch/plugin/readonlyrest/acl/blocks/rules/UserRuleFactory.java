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
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeySha1SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeySha256SyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.AuthKeySyncRule;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.impl.ProxyAuthSyncRule;
import org.elasticsearch.plugin.readonlyrest.settings.AuthKeyProviderSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.AuthKeyPlainTextRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.AuthKeySha1RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.AuthKeySha256RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.ProxyAuthRuleSettings;

import java.util.Map;
import java.util.function.Function;

public class UserRuleFactory {

  private final Map<Class<? extends RuleSettings>, Function<RuleSettings, UserRule>> creators;
  private final ESContext context;

  public UserRuleFactory(ESContext context) {
    this.context = context;
    this.creators = Maps.newHashMap();
    this.creators.put(AuthKeySha1RuleSettings.class,
        settings -> new AuthKeySha1SyncRule((AuthKeySha1RuleSettings) settings, context));
    this.creators.put(AuthKeySha256RuleSettings.class,
        settings -> new AuthKeySha256SyncRule((AuthKeySha256RuleSettings) settings, context));
    this.creators.put(AuthKeyPlainTextRuleSettings.class,
        settings -> new AuthKeySyncRule((AuthKeyPlainTextRuleSettings) settings, context));
    this.creators.put(ProxyAuthRuleSettings.class,
        settings ->
            new ProxyAuthSyncRule((ProxyAuthRuleSettings) settings));
  }

  public UserRule create(AuthKeyProviderSettings settings) {
    Class<? extends RuleSettings> ruleSettingsClass = settings.getClass();
    if (creators.containsKey(ruleSettingsClass)) {
      return creators.get(ruleSettingsClass).apply(settings);
    } else {
      throw context.rorException("Cannot find rule for config class [" + ruleSettingsClass.getName() + "]");
    }
  }
}

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
import tech.beshu.ror.acl.ACL;
import tech.beshu.ror.acl.blocks.rules.impl.AuthKeySha1SyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.AuthKeySha256SyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.AuthKeySha512SyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.AuthKeySyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.AuthKeyUnixSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.JwtAuthSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.LdapAuthenticationAsyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.ProxyAuthSyncRule;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.settings.AuthKeyProviderSettings;
import tech.beshu.ror.settings.RuleSettings;
import tech.beshu.ror.settings.rules.AuthKeyPlainTextRuleSettings;
import tech.beshu.ror.settings.rules.AuthKeySha1RuleSettings;
import tech.beshu.ror.settings.rules.AuthKeySha256RuleSettings;
import tech.beshu.ror.settings.rules.AuthKeySha512RuleSettings;
import tech.beshu.ror.settings.rules.AuthKeyUnixRuleSettings;
import tech.beshu.ror.settings.rules.CacheSettings;
import tech.beshu.ror.settings.rules.JwtAuthRuleSettings;
import tech.beshu.ror.settings.rules.LdapAuthenticationRuleSettings;
import tech.beshu.ror.settings.rules.ProxyAuthRuleSettings;

import java.util.Map;
import java.util.function.Function;

public class UserRuleFactory {

  private final Map<Class<? extends RuleSettings>, Function<RuleSettings, ? extends AsyncRule>> creators;
  private final ESContext context;
  private final ACL acl;

  public UserRuleFactory(ESContext context, ACL acl) {
    this.acl = acl;
    this.context = context;
    this.creators = Maps.newHashMap();
    this.creators.put(
      AuthKeySha1RuleSettings.class,
      settings -> AsyncRuleAdapter.wrap(new AuthKeySha1SyncRule((AuthKeySha1RuleSettings) settings, context))
    );
    this.creators.put(
      AuthKeySha256RuleSettings.class,
      settings -> AsyncRuleAdapter.wrap(new AuthKeySha256SyncRule((AuthKeySha256RuleSettings) settings, context))
    );
    this.creators.put(
      AuthKeySha512RuleSettings.class,
      settings -> AsyncRuleAdapter.wrap(new AuthKeySha512SyncRule((AuthKeySha512RuleSettings) settings, context))
    );

    // Infinitely cached because the crypto is super heavy; the in-mem cache has salt+hashed keys for security.
    this.creators.put(
      AuthKeyUnixSyncRule.class,
      settings -> {
        AuthKeyUnixRuleSettings ruleSettings = (AuthKeyUnixRuleSettings) settings;
        AuthKeyUnixSyncRule rule = new AuthKeyUnixSyncRule(ruleSettings, context);
        return CachedAsyncAuthenticationDecorator.wrapInCacheIfCacheIsEnabled(rule, (CacheSettings)settings, context);
      }
    );

    this.creators.put(
      AuthKeyPlainTextRuleSettings.class,
      settings -> AsyncRuleAdapter.wrap(new AuthKeySyncRule((AuthKeyPlainTextRuleSettings) settings, context))
    );
    this.creators.put(
      ProxyAuthRuleSettings.class,
      settings -> AsyncRuleAdapter.wrap(new ProxyAuthSyncRule((ProxyAuthRuleSettings) settings))
    );

    this.creators.put(
      LdapAuthenticationRuleSettings.class,
      settings -> new LdapAuthenticationAsyncRule((LdapAuthenticationRuleSettings) settings, acl.getDefinitionsFactory(), context)
    );

    this.creators.put(
      JwtAuthRuleSettings.class,
      settings -> AsyncRuleAdapter.wrap(new JwtAuthSyncRule((JwtAuthRuleSettings) settings, context))
    );
  }

  public AsyncRule create(AuthKeyProviderSettings settings) {
    Class<? extends RuleSettings> ruleSettingsClass = settings.getClass();
    if (creators.containsKey(ruleSettingsClass)) {
      return creators.get(ruleSettingsClass).apply(settings);
    }
    else {
      throw context.rorException("Cannot find rule for config class [" + ruleSettingsClass.getName() + "]");
    }
  }
}

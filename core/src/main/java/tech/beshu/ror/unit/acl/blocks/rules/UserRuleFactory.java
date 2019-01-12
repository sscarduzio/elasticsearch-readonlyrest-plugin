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

package tech.beshu.ror.unit.acl.blocks.rules;

import com.google.common.collect.Maps;
import tech.beshu.ror.unit.acl.__old_ACL;
import tech.beshu.ror.unit.acl.blocks.rules.impl.__old_AuthKeySha1SyncRule;
import tech.beshu.ror.unit.acl.blocks.rules.impl.__old_AuthKeySha256SyncRule;
import tech.beshu.ror.unit.acl.blocks.rules.impl.__old_AuthKeySha512SyncRule;
import tech.beshu.ror.unit.acl.blocks.rules.impl.__old_AuthKeySyncRule;
import tech.beshu.ror.unit.acl.blocks.rules.impl.__old_AuthKeyUnixAsyncRule;
import tech.beshu.ror.unit.acl.blocks.rules.impl.JwtAuthSyncRule;
import tech.beshu.ror.unit.acl.blocks.rules.impl.LdapAuthenticationAsyncRule;
import tech.beshu.ror.unit.acl.blocks.rules.impl.__old_ProxyAuthSyncRule;
import tech.beshu.ror.unit.acl.blocks.rules.impl.RorKbnAuthSyncRule;
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
import tech.beshu.ror.settings.rules.__old_ProxyAuthRuleSettings;
import tech.beshu.ror.settings.rules.RorKbnAuthRuleSettings;

import java.util.Map;
import java.util.function.Function;

public class UserRuleFactory {

  private final Map<Class<? extends RuleSettings>, Function<RuleSettings, ? extends AsyncRule>> creators;
  private final ESContext context;
  private final __old_ACL acl;

  public UserRuleFactory(ESContext context, __old_ACL acl) {
    this.acl = acl;
    this.context = context;
    this.creators = Maps.newHashMap();
    this.creators.put(
        AuthKeySha1RuleSettings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_AuthKeySha1SyncRule((AuthKeySha1RuleSettings) settings, context))
    );
    this.creators.put(
        AuthKeySha256RuleSettings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_AuthKeySha256SyncRule((AuthKeySha256RuleSettings) settings, context))
    );
    this.creators.put(
        AuthKeySha512RuleSettings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_AuthKeySha512SyncRule((AuthKeySha512RuleSettings) settings, context))
    );

    // Infinitely cached because the crypto is super heavy; the in-mem cache has salt+hashed keys for security.
    this.creators.put(
        AuthKeyUnixRuleSettings.class,
        settings -> {
          AuthKeyUnixRuleSettings ruleSettings = (AuthKeyUnixRuleSettings) settings;
          __old_AuthKeyUnixAsyncRule rule = new __old_AuthKeyUnixAsyncRule(ruleSettings, context);
          return CachedAsyncAuthenticationDecorator.wrapInCacheIfCacheIsEnabled(rule, (CacheSettings) settings, context);
        }
    );

    this.creators.put(
        AuthKeyPlainTextRuleSettings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_AuthKeySyncRule((AuthKeyPlainTextRuleSettings) settings, context))
    );
    this.creators.put(
        __old_ProxyAuthRuleSettings.class,
        settings -> AsyncRuleAdapter.wrap(new __old_ProxyAuthSyncRule((__old_ProxyAuthRuleSettings) settings))
    );

    this.creators.put(
        LdapAuthenticationRuleSettings.class,
        settings -> new LdapAuthenticationAsyncRule((LdapAuthenticationRuleSettings) settings, acl.getDefinitionsFactory(), context)
    );

    this.creators.put(
        JwtAuthRuleSettings.class,
        settings -> new JwtAuthSyncRule((JwtAuthRuleSettings) settings, context, acl.getDefinitionsFactory())
    );
    this.creators.put(
        RorKbnAuthRuleSettings.class,
        settings -> AsyncRuleAdapter.wrap(new RorKbnAuthSyncRule((RorKbnAuthRuleSettings) settings, context))
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

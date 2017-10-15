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
package tech.beshu.ror.settings;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import tech.beshu.ror.commons.RawSettings;
import tech.beshu.ror.commons.SettingsMalformedException;
import tech.beshu.ror.settings.definitions.LdapSettingsCollection;
import tech.beshu.ror.settings.definitions.ProxyAuthDefinitionSettingsCollection;
import tech.beshu.ror.settings.rules.AuthKeyPlainTextRuleSettings;
import tech.beshu.ror.settings.rules.AuthKeySha1RuleSettings;
import tech.beshu.ror.settings.rules.AuthKeySha256RuleSettings;
import tech.beshu.ror.settings.rules.AuthKeySha512RuleSettings;
import tech.beshu.ror.settings.rules.AuthKeyUnixRuleSettings;
import tech.beshu.ror.settings.rules.JwtAuthRuleSettings;
import tech.beshu.ror.settings.rules.LdapAuthenticationRuleSettings;
import tech.beshu.ror.settings.rules.ProxyAuthRuleSettings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class AuthMethodCreatorsRegistry {

  private final Map<String, Function<RawSettings, AuthKeyProviderSettings>> authKeyProviderCreators;

  public AuthMethodCreatorsRegistry(
    ProxyAuthDefinitionSettingsCollection proxyAuthDefinitionSettingsCollection,
    LdapSettingsCollection ldapSettingsCollection
  ) {

    HashMap<String, Function<RawSettings, AuthKeyProviderSettings>> creators = Maps.newHashMap();
    creators.put(AuthKeyPlainTextRuleSettings.ATTRIBUTE_NAME, authKeySettingsCreator());
    creators.put(AuthKeySha1RuleSettings.ATTRIBUTE_NAME, authKeySha1SettingsCreator());
    creators.put(AuthKeySha256RuleSettings.ATTRIBUTE_NAME, authKeySha256SettingsCreator());
    creators.put(AuthKeySha512RuleSettings.ATTRIBUTE_NAME, authKeySha512SettingsCreator());
    creators.put(AuthKeyUnixRuleSettings.ATTRIBUTE_NAME, authKeyUnixSettingsCreator());
    creators.put(ProxyAuthRuleSettings.ATTRIBUTE_NAME, proxyAuthSettingsCreator(proxyAuthDefinitionSettingsCollection));
    creators.put(LdapAuthenticationRuleSettings.ATTRIBUTE_NAME, ldapAuthenticationRuleSettingsCreator(ldapSettingsCollection));
    creators.put(JwtAuthRuleSettings.ATTRIBUTE_NAME, jwtAuthSettingsCreator());
    this.authKeyProviderCreators = creators;
  }

  public AuthKeyProviderSettings create(String name, RawSettings settings) {
    if (!authKeyProviderCreators.containsKey(name)) {
      throw new SettingsMalformedException("Unknown auth method name: '" + name + "'");
    }
    return authKeyProviderCreators.get(name).apply(settings);
  }

  @SuppressWarnings("unchecked")
  private Function<RawSettings, AuthKeyProviderSettings> authKeySettingsCreator() {
    return settings -> AuthKeyPlainTextRuleSettings.from(settings.stringReq(AuthKeyPlainTextRuleSettings.ATTRIBUTE_NAME));
  }

  @SuppressWarnings("unchecked")
  private Function<RawSettings, AuthKeyProviderSettings> authKeySha1SettingsCreator() {
    return settings -> AuthKeySha1RuleSettings.from(settings.stringReq(AuthKeySha1RuleSettings.ATTRIBUTE_NAME));
  }

  @SuppressWarnings("unchecked")
  private Function<RawSettings, AuthKeyProviderSettings> authKeySha256SettingsCreator() {
    return settings -> AuthKeySha256RuleSettings.from(settings.stringReq(AuthKeySha256RuleSettings.ATTRIBUTE_NAME));
  }

  @SuppressWarnings("unchecked")
  private Function<RawSettings, AuthKeyProviderSettings> authKeySha512SettingsCreator() {
    return settings -> AuthKeySha512RuleSettings.from(settings.stringReq(AuthKeySha512RuleSettings.ATTRIBUTE_NAME));
  }

  @SuppressWarnings("unchecked")
  private Function<RawSettings, AuthKeyProviderSettings> authKeyUnixSettingsCreator() {
    return settings -> AuthKeyUnixRuleSettings.from(settings.stringReq(AuthKeyUnixRuleSettings.ATTRIBUTE_NAME));
  }

  @SuppressWarnings("unchecked")
  private Function<RawSettings, AuthKeyProviderSettings> proxyAuthSettingsCreator(
    ProxyAuthDefinitionSettingsCollection proxyAuthDefinitionSettingsCollection) {
    return settings -> {
      Object s = settings.req(ProxyAuthRuleSettings.ATTRIBUTE_NAME);
      if (s instanceof List<?>) {
        return ProxyAuthRuleSettings.from((List<String>) s);
      }
      else if (s instanceof String) {
        return ProxyAuthRuleSettings.from(Lists.newArrayList((String) s));
      }
      else {
        return ProxyAuthRuleSettings.from(new RawSettings((Map<String, ?>) s), proxyAuthDefinitionSettingsCollection);
      }
    };
  }

  private Function<RawSettings, AuthKeyProviderSettings> jwtAuthSettingsCreator() {
    return settings -> {
      RawSettings conf = settings.inner(JwtAuthRuleSettings.ATTRIBUTE_NAME);
      return JwtAuthRuleSettings.from(conf);
    };
  }


  @SuppressWarnings("unchecked")
  private Function<RawSettings, AuthKeyProviderSettings> ldapAuthenticationRuleSettingsCreator(LdapSettingsCollection ldapSettingsCollection) {
    return (rawSettings) -> {
      Object settings = rawSettings.req(LdapAuthenticationRuleSettings.ATTRIBUTE_NAME);
      return settings instanceof String
        ? LdapAuthenticationRuleSettings.from((String) settings, ldapSettingsCollection)
        : LdapAuthenticationRuleSettings.from(new RawSettings((Map<String, ?>) settings), ldapSettingsCollection);
    };

  }

}

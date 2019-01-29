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
import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.commons.settings.SettingsMalformedException;
import tech.beshu.ror.settings.definitions.__old_JwtAuthDefinitionSettingsCollection;
import tech.beshu.ror.settings.definitions.LdapSettingsCollection;
import tech.beshu.ror.settings.definitions.__old_ProxyAuthDefinitionSettingsCollection;
import tech.beshu.ror.settings.definitions.RorKbnAuthDefinitionSettingsCollection;
import tech.beshu.ror.settings.rules.__old_AuthKeyPlainTextRuleSettings;
import tech.beshu.ror.settings.rules.__old_AuthKeySha1RuleSettings;
import tech.beshu.ror.settings.rules.__old_AuthKeySha256RuleSettings;
import tech.beshu.ror.settings.rules.__old_AuthKeySha512RuleSettings;
import tech.beshu.ror.settings.rules.__old_AuthKeyUnixRuleSettings;
import tech.beshu.ror.settings.rules.__old_JwtAuthRuleSettings;
import tech.beshu.ror.settings.rules.LdapAuthenticationRuleSettings;
import tech.beshu.ror.settings.rules.__old_ProxyAuthRuleSettings;
import tech.beshu.ror.settings.rules.RorKbnAuthRuleSettings;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public class AuthMethodCreatorsRegistry {

  private final Map<String, Function<RawSettings, AuthKeyProviderSettings>> authKeyProviderCreators;

  public AuthMethodCreatorsRegistry(
    __old_ProxyAuthDefinitionSettingsCollection proxyAuthDefinitionSettingsCollection,
    LdapSettingsCollection ldapSettingsCollection,
    __old_JwtAuthDefinitionSettingsCollection jwtAuthDefinitionSettingsCollection,
    RorKbnAuthDefinitionSettingsCollection rorKbnAuthDefinitionSettingsCollection
  ) {

    HashMap<String, Function<RawSettings, AuthKeyProviderSettings>> creators = Maps.newHashMap();
    creators.put(__old_AuthKeyPlainTextRuleSettings.ATTRIBUTE_NAME, authKeySettingsCreator());
    creators.put(__old_AuthKeySha1RuleSettings.ATTRIBUTE_NAME, authKeySha1SettingsCreator());
    creators.put(__old_AuthKeySha256RuleSettings.ATTRIBUTE_NAME, authKeySha256SettingsCreator());
    creators.put(__old_AuthKeySha512RuleSettings.ATTRIBUTE_NAME, authKeySha512SettingsCreator());
    creators.put(__old_AuthKeyUnixRuleSettings.ATTRIBUTE_NAME, authKeyUnixSettingsCreator());
    creators.put(__old_ProxyAuthRuleSettings.ATTRIBUTE_NAME, proxyAuthSettingsCreator(proxyAuthDefinitionSettingsCollection));
    creators.put(LdapAuthenticationRuleSettings.ATTRIBUTE_NAME, ldapAuthenticationRuleSettingsCreator(ldapSettingsCollection));
    creators.put(__old_JwtAuthRuleSettings.ATTRIBUTE_NAME, jwtAuthSettingsCreator(jwtAuthDefinitionSettingsCollection));
    creators.put(RorKbnAuthRuleSettings.ATTRIBUTE_NAME, rorKbnAuthSettingsCreator(rorKbnAuthDefinitionSettingsCollection));
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
    return settings -> __old_AuthKeyPlainTextRuleSettings.from(settings.stringReq(__old_AuthKeyPlainTextRuleSettings.ATTRIBUTE_NAME));
  }

  @SuppressWarnings("unchecked")
  private Function<RawSettings, AuthKeyProviderSettings> authKeySha1SettingsCreator() {
    return settings -> __old_AuthKeySha1RuleSettings.from(settings.stringReq(__old_AuthKeySha1RuleSettings.ATTRIBUTE_NAME));
  }

  @SuppressWarnings("unchecked")
  private Function<RawSettings, AuthKeyProviderSettings> authKeySha256SettingsCreator() {
    return settings -> __old_AuthKeySha256RuleSettings.from(settings.stringReq(__old_AuthKeySha256RuleSettings.ATTRIBUTE_NAME));
  }

  @SuppressWarnings("unchecked")
  private Function<RawSettings, AuthKeyProviderSettings> authKeySha512SettingsCreator() {
    return settings -> __old_AuthKeySha512RuleSettings.from(settings.stringReq(__old_AuthKeySha512RuleSettings.ATTRIBUTE_NAME));
  }

  @SuppressWarnings("unchecked")
  private Function<RawSettings, AuthKeyProviderSettings> authKeyUnixSettingsCreator() {
    return settings -> __old_AuthKeyUnixRuleSettings.from(
      settings.stringReq(__old_AuthKeyUnixRuleSettings.ATTRIBUTE_NAME),
      Duration.ofSeconds(settings.intOpt(__old_AuthKeyUnixRuleSettings.ATTRIBUTE_AUTH_CACHE_TTL)
                           .orElse(__old_AuthKeyUnixRuleSettings.DEFAULT_CACHE_TTL))
    );
  }

  @SuppressWarnings("unchecked")
  private Function<RawSettings, AuthKeyProviderSettings> proxyAuthSettingsCreator(
    __old_ProxyAuthDefinitionSettingsCollection proxyAuthDefinitionSettingsCollection) {
    return settings -> {
      Object s = settings.req(__old_ProxyAuthRuleSettings.ATTRIBUTE_NAME);
      if (s instanceof List<?>) {
        return __old_ProxyAuthRuleSettings.from((List<String>) s);
      }
      else if (s instanceof String) {
        return __old_ProxyAuthRuleSettings.from(Lists.newArrayList((String) s));
      }
      else {
        return __old_ProxyAuthRuleSettings.from(new RawSettings((Map<String, ?>) s, settings.getLogger()), proxyAuthDefinitionSettingsCollection);
      }
    };
  }

  private Function<RawSettings, AuthKeyProviderSettings> jwtAuthSettingsCreator(
      __old_JwtAuthDefinitionSettingsCollection definitionSettingsCollection) {
    return settings -> {
      Object conf = settings.req(__old_JwtAuthRuleSettings.ATTRIBUTE_NAME);
      return conf instanceof String
          ? __old_JwtAuthRuleSettings.from((String) conf, definitionSettingsCollection)
          : __old_JwtAuthRuleSettings.from(new RawSettings((Map<String, ?>) conf, settings.getLogger()), definitionSettingsCollection);
    };
  }

  private Function<RawSettings, AuthKeyProviderSettings> rorKbnAuthSettingsCreator(
      RorKbnAuthDefinitionSettingsCollection definitionSettingsCollection) {
    return settings -> {
      Object conf = settings.req(RorKbnAuthRuleSettings.ATTRIBUTE_NAME);
      return conf instanceof String
          ? RorKbnAuthRuleSettings.from((String) conf, definitionSettingsCollection)
          : RorKbnAuthRuleSettings.from(new RawSettings((Map<String, ?>) conf, settings.getLogger()), definitionSettingsCollection);
    };
  }


  @SuppressWarnings("unchecked")
  private Function<RawSettings, AuthKeyProviderSettings> ldapAuthenticationRuleSettingsCreator(LdapSettingsCollection ldapSettingsCollection) {
    return (rawSettings) -> {
      Object settings = rawSettings.req(LdapAuthenticationRuleSettings.ATTRIBUTE_NAME);
      return settings instanceof String
        ? LdapAuthenticationRuleSettings.from((String) settings, ldapSettingsCollection)
        : LdapAuthenticationRuleSettings.from(new RawSettings((Map<String, ?>) settings, rawSettings.getLogger()), ldapSettingsCollection);
    };

  }

}

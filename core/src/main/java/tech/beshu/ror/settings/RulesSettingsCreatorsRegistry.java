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

import tech.beshu.ror.acl.blocks.rules.impl.__old_FieldsSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.FilterSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_HeadersAndSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_HeadersOrSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_HeadersSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_KibanaIndexSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.RepositoriesSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.SnapshotsSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_UriReSyncRule;
import tech.beshu.ror.acl.blocks.rules.impl.__old_UsersSyncRule;
import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.commons.settings.SettingsMalformedException;
import tech.beshu.ror.settings.definitions.ExternalAuthenticationServiceSettingsCollection;
import tech.beshu.ror.settings.definitions.LdapSettingsCollection;
import tech.beshu.ror.settings.definitions.UserGroupsProviderSettingsCollection;
import tech.beshu.ror.settings.definitions.UserSettingsCollection;
import tech.beshu.ror.settings.rules.__old_ActionsRuleSettings;
import tech.beshu.ror.settings.rules.__old_ApiKeysRuleSettings;
import tech.beshu.ror.settings.rules.AuthKeyPlainTextRuleSettings;
import tech.beshu.ror.settings.rules.AuthKeySha1RuleSettings;
import tech.beshu.ror.settings.rules.AuthKeySha256RuleSettings;
import tech.beshu.ror.settings.rules.AuthKeySha512RuleSettings;
import tech.beshu.ror.settings.rules.AuthKeyUnixRuleSettings;
import tech.beshu.ror.settings.rules.ExternalAuthenticationRuleSettings;
import tech.beshu.ror.settings.rules.GroupsProviderAuthorizationRuleSettings;
import tech.beshu.ror.settings.rules.GroupsRuleSettings;
import tech.beshu.ror.settings.rules.__old_HostsRuleSettings;
import tech.beshu.ror.settings.rules.IndicesRuleSettings;
import tech.beshu.ror.settings.rules.JwtAuthRuleSettings;
import tech.beshu.ror.settings.rules.KibanaAccessRuleSettings;
import tech.beshu.ror.settings.rules.KibanaHideAppsRuleSettings;
import tech.beshu.ror.settings.rules.LdapAuthRuleSettings;
import tech.beshu.ror.settings.rules.LdapAuthenticationRuleSettings;
import tech.beshu.ror.settings.rules.LdapAuthorizationRuleSettings;
import tech.beshu.ror.settings.rules.__old_LocalHostsRuleSettings;
import tech.beshu.ror.settings.rules.__old_MaxBodyLengthRuleSettings;
import tech.beshu.ror.settings.rules.MethodsRuleSettings;
import tech.beshu.ror.settings.rules.__old_ProxyAuthRuleSettings;
import tech.beshu.ror.settings.rules.RorKbnAuthRuleSettings;
import tech.beshu.ror.settings.rules.SearchlogRuleSettings;
import tech.beshu.ror.settings.rules.SessionMaxIdleRuleSettings;
import tech.beshu.ror.settings.rules.__old_XForwardedForRuleSettings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public class RulesSettingsCreatorsRegistry {

  private final Map<String, Supplier<RuleSettings>> ruleSettingsCreators;

  RulesSettingsCreatorsRegistry(RawSettings blockSettings,
      AuthMethodCreatorsRegistry authMethodCreatorsRegistry,
      LdapSettingsCollection ldapSettingsCollection,
      UserGroupsProviderSettingsCollection groupsProviderSettingsGroup,
      ExternalAuthenticationServiceSettingsCollection externalAuthenticationServiceSettingsCollection,
      UserSettingsCollection userSettingsCollection) {
    Map<String, Supplier<RuleSettings>> creators = new HashMap<>();
    creators.put(
        LdapAuthRuleSettings.ATTRIBUTE_NAME,
        ldapAuthRuleSettingsCreator(blockSettings, ldapSettingsCollection)
    );
    creators.put(
        LdapAuthenticationRuleSettings.ATTRIBUTE_NAME,
        ldapAuthenticationRuleSettingsCreator(blockSettings, ldapSettingsCollection)
    );
    creators.put(
        LdapAuthorizationRuleSettings.ATTRIBUTE_NAME,
        ldapAuthorizationRuleSettingsCreator(blockSettings, ldapSettingsCollection)
    );
    creators.put(
        GroupsProviderAuthorizationRuleSettings.ATTRIBUTE_NAME,
        groupsProviderAuthorizationRuleSettingsCreator(blockSettings, groupsProviderSettingsGroup)
    );
    creators.put(
        ExternalAuthenticationRuleSettings.ATTRIBUTE_NAME,
        externalAuthenticationSettingsCreator(blockSettings, externalAuthenticationServiceSettingsCollection)
    );
    creators.put(IndicesRuleSettings.ATTRIBUTE_NAME, indicesSettingsCreator(blockSettings));
    creators.put(MethodsRuleSettings.ATTRIBUTE_NAME, methodsSettingsCreator(blockSettings));
    creators.put(__old_HeadersSyncRule.Settings.ATTRIBUTE_NAME, headersSettingsCreator(blockSettings));
    creators.put(__old_HeadersAndSyncRule.Settings.ATTRIBUTE_NAME, headersAndSettingsCreator(blockSettings));
    creators.put(__old_HeadersOrSyncRule.Settings.ATTRIBUTE_NAME, headersOrSettingsCreator(blockSettings));
    creators.put(__old_ActionsRuleSettings.ATTRIBUTE_NAME, actionsSettingsCreator(blockSettings));
    creators.put(__old_HostsRuleSettings.ATTRIBUTE_NAME, hostsSettingsCreator(blockSettings));
    creators.put(__old_LocalHostsRuleSettings.ATTRIBUTE_NAME, localHostsSettingsCreator(blockSettings));
    creators.put(SnapshotsSyncRule.Settings.ATTRIBUTE_NAME, snapshotsRuleSettingsCreator(blockSettings));
    creators.put(RepositoriesSyncRule.Settings.ATTRIBUTE_NAME, repositoriesRuleSettingsCreator(blockSettings));
    creators.put(
        __old_ProxyAuthRuleSettings.ATTRIBUTE_NAME, proxyAuthSettingsCreator(blockSettings, authMethodCreatorsRegistry));
    creators.put(AuthKeyPlainTextRuleSettings.ATTRIBUTE_NAME, authKeySettingsCreator(blockSettings, authMethodCreatorsRegistry));
    creators.put(AuthKeySha1RuleSettings.ATTRIBUTE_NAME, authKeySha1SettingsCreator(blockSettings, authMethodCreatorsRegistry));
    creators.put(AuthKeySha256RuleSettings.ATTRIBUTE_NAME, authKeySha256SettingsCreator(blockSettings, authMethodCreatorsRegistry));
    creators.put(AuthKeySha512RuleSettings.ATTRIBUTE_NAME, authKeySha512SettingsCreator(blockSettings, authMethodCreatorsRegistry));
    creators.put(AuthKeyUnixRuleSettings.ATTRIBUTE_NAME, authKeyUnixSettingsCreator(blockSettings, authMethodCreatorsRegistry));
    creators.put(KibanaAccessRuleSettings.ATTRIBUTE_NAME, kibanaAccessSettingsCreator(blockSettings));
    creators.put(__old_KibanaIndexSyncRule.Settings.ATTRIBUTE_NAME, kibanaIndexSettingsCreator(blockSettings));
    creators.put(__old_FieldsSyncRule.Settings.ATTRIBUTE_NAME, fieldsSettingsCreator(blockSettings));
    creators.put(FilterSyncRule.Settings.ATTRIBUTE_NAME, filterSettingsCreator(blockSettings));
    creators.put(KibanaHideAppsRuleSettings.ATTRIBUTE_NAME, kibanaHideAppsSettingsCreator(blockSettings));
    creators.put(__old_ApiKeysRuleSettings.ATTRIBUTE_NAME, apiKeysSettingsCreator(blockSettings));
    creators.put(__old_MaxBodyLengthRuleSettings.ATTRIBUTE_NAME, maxBodyLengthSettingsCreator(blockSettings));
    creators.put(SessionMaxIdleRuleSettings.ATTRIBUTE_NAME, sessionMaxIdleSettingsCreator(blockSettings));
    creators.put(SearchlogRuleSettings.ATTRIBUTE_NAME, searchlogSettingsCreator(blockSettings));
    creators.put(__old_UriReSyncRule.Settings.ATTRIBUTE_NAME, uriReSettingsCreator(blockSettings));
    creators.put(__old_UsersSyncRule.Settings.ATTRIBUTE_NAME, usersSettingsCreator(blockSettings));
    creators.put(__old_XForwardedForRuleSettings.ATTRIBUTE_NAME, xForwardedForSettingsCreator(blockSettings));
    creators.put(GroupsRuleSettings.ATTRIBUTE_NAME, groupsSettingsCreator(blockSettings, userSettingsCollection));
    creators.put(JwtAuthRuleSettings.ATTRIBUTE_NAME, jwtAuthSettingsCreator(blockSettings, authMethodCreatorsRegistry));
    creators.put(RorKbnAuthRuleSettings.ATTRIBUTE_NAME, rorKbnAuthSettingsCreator(blockSettings, authMethodCreatorsRegistry));

    this.ruleSettingsCreators = creators;
  }

  public RuleSettings create(String name) {
    if (!ruleSettingsCreators.containsKey(name)) {
      throw new SettingsMalformedException("Unknown rule name: '" + name + "'");
    }
    return ruleSettingsCreators.get(name).get();
  }

  private Supplier<RuleSettings> ldapAuthRuleSettingsCreator(RawSettings blockSettings,
      LdapSettingsCollection ldapSettingsCollection) {
    return () -> LdapAuthRuleSettings.from(blockSettings.inner(LdapAuthRuleSettings.ATTRIBUTE_NAME), ldapSettingsCollection);
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> ldapAuthenticationRuleSettingsCreator(RawSettings blockSettings,
      LdapSettingsCollection ldapSettingsCollection) {
    return () -> {
      Object settings = blockSettings.req(LdapAuthenticationRuleSettings.ATTRIBUTE_NAME);
      return settings instanceof String
          ? LdapAuthenticationRuleSettings.from((String) settings, ldapSettingsCollection)
          : LdapAuthenticationRuleSettings.from(new RawSettings((Map<String, ?>) settings, blockSettings.getLogger()), ldapSettingsCollection);
    };
  }

  private Supplier<RuleSettings> ldapAuthorizationRuleSettingsCreator(RawSettings blockSettings,
      LdapSettingsCollection ldapSettingsCollection) {
    return () -> LdapAuthorizationRuleSettings.from(
        blockSettings.inner(LdapAuthorizationRuleSettings.ATTRIBUTE_NAME),
        ldapSettingsCollection
    );
  }

  private Supplier<RuleSettings> groupsProviderAuthorizationRuleSettingsCreator(
      RawSettings blockSettings,
      UserGroupsProviderSettingsCollection userGroupsProviderSettingsCollection) {
    return () -> GroupsProviderAuthorizationRuleSettings.from(
        blockSettings.inner(GroupsProviderAuthorizationRuleSettings.ATTRIBUTE_NAME),
        userGroupsProviderSettingsCollection
    );
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> externalAuthenticationSettingsCreator(RawSettings blockSettings,
      ExternalAuthenticationServiceSettingsCollection externalAuthenticationServiceSettingsCollection) {
    return () -> {
      Object settings = blockSettings.req(ExternalAuthenticationRuleSettings.ATTRIBUTE_NAME);
      return settings instanceof String
          ? ExternalAuthenticationRuleSettings.from(
          (String) settings,
          externalAuthenticationServiceSettingsCollection
      )
          : ExternalAuthenticationRuleSettings.from(
          new RawSettings((Map<String, ?>) settings, blockSettings.getLogger()),
          externalAuthenticationServiceSettingsCollection
      );
    };
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> indicesSettingsCreator(RawSettings blockSettings) {
    return () -> IndicesRuleSettings.from(
        (Set<String>) blockSettings.notEmptySetReq(IndicesRuleSettings.ATTRIBUTE_NAME)
    );
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> methodsSettingsCreator(RawSettings blockSettings) {
    return () -> MethodsRuleSettings.from(
        (Set<String>) blockSettings.notEmptySetReq(MethodsRuleSettings.ATTRIBUTE_NAME)
    );
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> headersSettingsCreator(RawSettings blockSettings) {
    return () -> new __old_HeadersSyncRule.Settings(
        (Set<String>) blockSettings.notEmptySetReq(__old_HeadersSyncRule.Settings.ATTRIBUTE_NAME)
    );
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> headersOrSettingsCreator(RawSettings blockSettings) {
    return () -> new __old_HeadersOrSyncRule.Settings(
        (Set<String>) blockSettings.notEmptySetReq(__old_HeadersOrSyncRule.Settings.ATTRIBUTE_NAME)
    );
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> headersAndSettingsCreator(RawSettings blockSettings) {
    return () -> new __old_HeadersAndSyncRule.Settings(
        (Set<String>) blockSettings.notEmptySetReq(__old_HeadersAndSyncRule.Settings.ATTRIBUTE_NAME)
    );
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> actionsSettingsCreator(RawSettings blockSettings) {
    return () -> __old_ActionsRuleSettings.from(
        (Set<String>) blockSettings.notEmptySetReq(__old_ActionsRuleSettings.ATTRIBUTE_NAME)
    );
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> hostsSettingsCreator(RawSettings blockSettings) {
    return () -> __old_HostsRuleSettings.fromBlockSettings(blockSettings);
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> localHostsSettingsCreator(RawSettings blockSettings) {
    return () -> __old_LocalHostsRuleSettings.fromBlockSettings(blockSettings);
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> snapshotsRuleSettingsCreator(RawSettings blockSettings) {
    return () -> SnapshotsSyncRule.Settings.fromBlockSettings(blockSettings);
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> repositoriesRuleSettingsCreator(RawSettings blockSettings) {
    return () -> RepositoriesSyncRule.Settings.fromBlockSettings(blockSettings);
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> proxyAuthSettingsCreator(RawSettings blockSettings,
      AuthMethodCreatorsRegistry authMethodCreatorsRegistry) {
    return authRuleFrom(__old_ProxyAuthRuleSettings.ATTRIBUTE_NAME, blockSettings, authMethodCreatorsRegistry);
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> authKeySettingsCreator(RawSettings blockSettings,
      AuthMethodCreatorsRegistry authMethodCreatorsRegistry) {
    return authRuleFrom(AuthKeyPlainTextRuleSettings.ATTRIBUTE_NAME, blockSettings, authMethodCreatorsRegistry);
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> authKeySha1SettingsCreator(RawSettings blockSettings,
      AuthMethodCreatorsRegistry authMethodCreatorsRegistry) {
    return authRuleFrom(AuthKeySha1RuleSettings.ATTRIBUTE_NAME, blockSettings, authMethodCreatorsRegistry);
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> authKeySha256SettingsCreator(RawSettings blockSettings,
      AuthMethodCreatorsRegistry authMethodCreatorsRegistry) {
    return authRuleFrom(AuthKeySha256RuleSettings.ATTRIBUTE_NAME, blockSettings, authMethodCreatorsRegistry);
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> authKeySha512SettingsCreator(RawSettings blockSettings,
      AuthMethodCreatorsRegistry authMethodCreatorsRegistry) {
    return authRuleFrom(AuthKeySha512RuleSettings.ATTRIBUTE_NAME, blockSettings, authMethodCreatorsRegistry);
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> authKeyUnixSettingsCreator(RawSettings blockSettings,
      AuthMethodCreatorsRegistry authMethodCreatorsRegistry) {
    return authRuleFrom(AuthKeyUnixRuleSettings.ATTRIBUTE_NAME, blockSettings, authMethodCreatorsRegistry);
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> kibanaAccessSettingsCreator(RawSettings blockSettings) {
    return () -> KibanaAccessRuleSettings.fromBlockSettings(blockSettings);
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> kibanaIndexSettingsCreator(RawSettings blockSettings) {
    return () -> __old_KibanaIndexSyncRule.Settings.fromBlockSettings(blockSettings);
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> fieldsSettingsCreator(RawSettings blockSettings) {
    return () -> __old_FieldsSyncRule.Settings.fromBlockSettings(blockSettings);
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> filterSettingsCreator(RawSettings blockSettings) {
    return () -> FilterSyncRule.Settings.fromBlockSettings(blockSettings);
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> kibanaHideAppsSettingsCreator(RawSettings blockSettings) {
    return () -> KibanaHideAppsRuleSettings.from(
        (Set<String>) blockSettings.notEmptySetReq(KibanaHideAppsRuleSettings.ATTRIBUTE_NAME)
    );
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> apiKeysSettingsCreator(RawSettings blockSettings) {
    return () -> __old_ApiKeysRuleSettings.from(
        (Set<String>) blockSettings.notEmptySetReq(__old_ApiKeysRuleSettings.ATTRIBUTE_NAME)
    );
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> maxBodyLengthSettingsCreator(RawSettings blockSettings) {
    return () -> __old_MaxBodyLengthRuleSettings.from(
        blockSettings.intReq(__old_MaxBodyLengthRuleSettings.ATTRIBUTE_NAME)
    );
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> searchlogSettingsCreator(RawSettings blockSettings) {
    return () -> SearchlogRuleSettings.from(
        blockSettings.booleanReq(SearchlogRuleSettings.ATTRIBUTE_NAME)
    );
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> sessionMaxIdleSettingsCreator(RawSettings blockSettings) {
    return () -> SessionMaxIdleRuleSettings.from(
        blockSettings.stringReq(SessionMaxIdleRuleSettings.ATTRIBUTE_NAME)
    );
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> uriReSettingsCreator(RawSettings blockSettings) {
    return () -> __old_UriReSyncRule.Settings.from(
        blockSettings.stringReq(__old_UriReSyncRule.Settings.ATTRIBUTE_NAME)
    );
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> usersSettingsCreator(RawSettings blockSettings) {
    return () -> __old_UsersSyncRule.Settings.from(
        (List<String>) blockSettings.notEmptyListReq(__old_UsersSyncRule.Settings.ATTRIBUTE_NAME)
    );
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> xForwardedForSettingsCreator(RawSettings blockSettings) {
    return () -> __old_XForwardedForRuleSettings.from(
        (List<String>) blockSettings.notEmptyListReq(__old_XForwardedForRuleSettings.ATTRIBUTE_NAME)
    );
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> groupsSettingsCreator(RawSettings blockSettings,
      UserSettingsCollection userSettingsCollection) {
    return () -> GroupsRuleSettings.from(
        (Set<String>) blockSettings.notEmptySetReq(GroupsRuleSettings.ATTRIBUTE_NAME),
        userSettingsCollection
    );
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> jwtAuthSettingsCreator(RawSettings blockSettings,
      AuthMethodCreatorsRegistry authMethodCreatorsRegistry) {
    return authRuleFrom(JwtAuthRuleSettings.ATTRIBUTE_NAME, blockSettings, authMethodCreatorsRegistry);
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> rorKbnAuthSettingsCreator(RawSettings blockSettings,
      AuthMethodCreatorsRegistry authMethodCreatorsRegistry) {
    return authRuleFrom(RorKbnAuthRuleSettings.ATTRIBUTE_NAME, blockSettings, authMethodCreatorsRegistry);
  }

  private Supplier<RuleSettings> authRuleFrom(String attribute, RawSettings settings,
      AuthMethodCreatorsRegistry authMethodCreatorsRegistry) {
    return () -> {
      AuthKeyProviderSettings authKeyProviderSettings = authMethodCreatorsRegistry.create(attribute, settings);
      if (!(authKeyProviderSettings instanceof RuleSettings)) {
        throw new SettingsMalformedException("No rule for auth method: " + attribute);
      }
      return (RuleSettings) authKeyProviderSettings;
    };
  }

}

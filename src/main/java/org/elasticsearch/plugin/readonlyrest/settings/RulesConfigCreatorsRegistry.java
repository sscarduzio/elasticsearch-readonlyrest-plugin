package org.elasticsearch.plugin.readonlyrest.settings;

import org.elasticsearch.plugin.readonlyrest.settings.definitions.ExternalAuthenticationServiceSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.LdapSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.ProxyAuthConfigSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.UserGroupsProviderSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.rules.ExternalAuthenticationRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.GroupsProviderAuthorizationRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.IndicesRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.LdapAuthRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.LdapAuthenticationRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.LdapAuthorizationRuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.ProxyAuthRuleSettings;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public class RulesConfigCreatorsRegistry {

  private final Map<String, Supplier<RuleSettings>> ruleSettingsCreators;

  RulesConfigCreatorsRegistry(RawSettings blockSettings,
                              LdapSettingsCollection ldapSettingsCollection,
                              UserGroupsProviderSettingsCollection groupsProviderSettingsGroup,
                              ProxyAuthConfigSettingsCollection proxyAuthConfigSettingsCollection,
                              ExternalAuthenticationServiceSettingsCollection externalAuthenticationServiceSettingsCollection) {
    Map<String, Supplier<RuleSettings>> creators = new HashMap<>();
    creators.put(LdapAuthRuleSettings.ATTRIBUTE_NAME,
        ldapAuthRuleSettingsCreator(blockSettings, ldapSettingsCollection));
    creators.put(LdapAuthenticationRuleSettings.ATTRIBUTE_NAME,
        ldapAuthenticationRuleSettingsCreator(blockSettings, ldapSettingsCollection));
    creators.put(LdapAuthorizationRuleSettings.ATTRIBUTE_NAME,
        ldapAuthorizationRuleSettingsCreator(blockSettings, ldapSettingsCollection));
    creators.put(GroupsProviderAuthorizationRuleSettings.ATTRIBUTE_NAME,
        groupsProviderAuthorizationRuleSettingsCreator(blockSettings, groupsProviderSettingsGroup));
    creators.put(ProxyAuthRuleSettings.ATTRIBUTE_NAME,
        proxyAuthSettingsCreator(blockSettings, proxyAuthConfigSettingsCollection));
    creators.put(ExternalAuthenticationRuleSettings.ATTRIBUTE_NAME,
        externalAuthenticationSettingsCreator(blockSettings, externalAuthenticationServiceSettingsCollection));
    creators.put(IndicesRuleSettings.ATTRIBUTE_NAME, indicesSettingsCreator(blockSettings));
    this.ruleSettingsCreators = creators;
  }

  public RuleSettings create(String name) {
    if (!ruleSettingsCreators.containsKey(name)) {
      throw new ConfigMalformedException("Unknown rule name: '" + name + "'");
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
          : LdapAuthenticationRuleSettings.from(new RawSettings((Map<String, ?>) settings), ldapSettingsCollection);
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
  private Supplier<RuleSettings> proxyAuthSettingsCreator(RawSettings blockSettings,
                                                          ProxyAuthConfigSettingsCollection proxyAuthConfigSettingsCollection) {
    return () -> {
      Object settings = blockSettings.req(ProxyAuthRuleSettings.ATTRIBUTE_NAME);
      return settings instanceof List<?>
          ? ProxyAuthRuleSettings.from((List<String>) settings)
          : ProxyAuthRuleSettings.from(new RawSettings((Map<String, ?>) settings), proxyAuthConfigSettingsCollection);
    };
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> externalAuthenticationSettingsCreator(RawSettings blockSettings,
                                                                       ExternalAuthenticationServiceSettingsCollection externalAuthenticationServiceSettingsCollection) {
    return () -> {
      Object settings = blockSettings.req(ExternalAuthenticationRuleSettings.ATTRIBUTE_NAME);
      return settings instanceof String
          ? ExternalAuthenticationRuleSettings.from((String) settings, externalAuthenticationServiceSettingsCollection)
          : ExternalAuthenticationRuleSettings.from(
          new RawSettings((Map<String, ?>) settings),
          externalAuthenticationServiceSettingsCollection);
    };
  }

  @SuppressWarnings("unchecked")
  private Supplier<RuleSettings> indicesSettingsCreator(RawSettings blockSettings) {
    return () -> IndicesRuleSettings.from(
        (Set<String>) blockSettings.notEmptySetReq(IndicesRuleSettings.ATTRIBUTE_NAME)
    );
  }

}

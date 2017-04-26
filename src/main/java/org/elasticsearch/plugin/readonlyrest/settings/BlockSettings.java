package org.elasticsearch.plugin.readonlyrest.settings;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.Lists;
import org.elasticsearch.plugin.readonlyrest.utils.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class BlockSettings extends Settings {

  public static final String ATTIBUTE_NAME = "access_control_rules";

  @JsonProperty("name")
  private String name;

  private List<RuleSettings> rules = Lists.newArrayList();

  public BlockSettings(String name, List<RuleSettings> rules) {
    this.name = name;
    this.rules = rules;
  }

  public BlockSettings withRule(RuleSettings rule) {
    ArrayList<RuleSettings> copied = Lists.newArrayList(rules);
    copied.add(rule);
    return new BlockSettings(name, copied);
  }

  @JsonProperty("ldap_auth")
  private Optional<LdapAuthRuleSettings> ldapRuleSettings = Optional.empty();

  @JsonProperty("ldap_authentication")
  private Optional<LdapAuthenticationRuleSettings> ldapAuthenticationRuleSettings = Optional.empty();

  @JsonProperty("ldap_authorization")
  private Optional<LdapAuthorizationRuleSettings> ldapAuthorizationRuleSettings = Optional.empty();

  @JsonProperty("groups_provider_authorization")
  private Optional<GroupsProviderAuthorizationRuleSettings> groupsProviderAuthorizationRuleSettings = Optional.empty();

  public String getName() {
    return name;
  }

  public Optional<LdapAuthRuleSettings> getLdapRuleSettings() {
    return ldapRuleSettings;
  }

//  void updateWithLdapSettings(List<LdapSettings> ldapsSettings) {
//    updateRuleSettingsWithLdapSettings(ldapsSettings, ldapRuleSettings.map(r -> (LdapSettingsDependent)r));
//    updateRuleSettingsWithLdapSettings(ldapsSettings, ldapAuthenticationRuleSettings.map(r -> (LdapSettingsDependent)r));
//    updateRuleSettingsWithLdapSettings(ldapsSettings, ldapAuthorizationRuleSettings.map(r -> (LdapSettingsDependent)r));
//  }
//
//  void updateWithGroupsProvidersSettings(List<UserGroupsProviderSettings> groupsProviderSettings) {
//    groupsProviderAuthorizationRuleSettings
//        .flatMap(r -> groupsProviderSettingsByName(groupsProviderSettings, r.getGroupsProviderName()).map(s -> Pair.create(r, s)))
//        .ifPresent(p -> p.getFirst().setUserGroupsProviderSettings(p.getSecond()));
//  }

//  @Override
//  protected void validate() {
//    if (name == null) {
//      throw new ConfigMalformedException("'name' was not defined in one of block definitions");
//    }
//    ldapRuleSettings.ifPresent(LdapAuthRuleSettings::validate);
//    ldapAuthenticationRuleSettings.ifPresent(LdapAuthenticationRuleSettings::validate);
//  }

  private void updateRuleSettingsWithLdapSettings(List<LdapSettings> ldapsSettings,
                                                  Optional<LdapSettingsDependent> ruleSettings) {
    ruleSettings
        .flatMap(r -> ldapSettingsByName(ldapsSettings, r.getName()).map(s -> Pair.create(r, s)))
        .ifPresent(p -> p.getFirst().setLdapSettings(p.getSecond()));
  }

  private Optional<LdapSettings> ldapSettingsByName(List<LdapSettings> ldapsSettings, String name) {
    return ldapsSettings.stream()
        .filter(l -> Objects.equals(l.getName(), name))
        .findFirst();
  }

  private Optional<UserGroupsProviderSettings> groupsProviderSettingsByName(List<UserGroupsProviderSettings> groupsProvidersSettings,
                                                                            String name) {
    return groupsProvidersSettings.stream()
        .filter(l -> Objects.equals(l.getName(), name))
        .findFirst();
  }
}

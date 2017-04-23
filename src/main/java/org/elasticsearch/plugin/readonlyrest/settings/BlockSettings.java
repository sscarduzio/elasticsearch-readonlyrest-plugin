package org.elasticsearch.plugin.readonlyrest.settings;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.utils.Pair;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public class BlockSettings extends Settings {

  @JsonProperty("name")
  private String name;

  @JsonProperty("ldap_auth")
  private Optional<LdapAuthRuleSettings> ldapRuleSettings = Optional.empty();

  @JsonProperty("ldap_authentication")
  private Optional<LdapAuthenticationRuleSettings> ldapAuthenticationRuleSettings = Optional.empty();

  @JsonProperty("ldap_authorization")
  private Optional<LdapAuthorizationRuleSettings> ldapAuthorizationRuleSettings = Optional.empty();

  public String getName() {
    return name;
  }

  public Optional<LdapAuthRuleSettings> getLdapRuleSettings() {
    return ldapRuleSettings;
  }

  void updateWithLdapSettings(List<LdapSettings> ldapsSettings) {
    updateRuleSettingsWithLdapSettings(ldapsSettings, ldapRuleSettings.map(r -> (LdapSettingsDependent)r));
    updateRuleSettingsWithLdapSettings(ldapsSettings, ldapAuthenticationRuleSettings.map(r -> (LdapSettingsDependent)r));
    updateRuleSettingsWithLdapSettings(ldapsSettings, ldapAuthorizationRuleSettings.map(r -> (LdapSettingsDependent)r));
  }

  @Override
  protected void validate() {
    if (name == null) {
      throw new ConfigMalformedException("'name' was not defined in one of block definitions");
    }
    ldapRuleSettings.ifPresent(LdapAuthRuleSettings::validate);
    ldapAuthenticationRuleSettings.ifPresent(LdapAuthenticationRuleSettings::validate);
  }

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
}

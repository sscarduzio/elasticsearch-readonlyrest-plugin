package org.elasticsearch.plugin.readonlyrest.settings;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;

import java.time.Duration;

public class LdapAuthenticationRuleSettings extends LdapSettingsDependent {

  @JsonProperty("name")
  private String name;

  @JsonProperty("cache_ttl_in_sec")
  private int cacheTtlInSec = 0;

  private LdapSettings ldapSettings;

  LdapAuthenticationRuleSettings() {}
  public LdapAuthenticationRuleSettings(String name) {
    this.name = name;
  }

  @Override
  String getName() {
    return name;
  }

  public Duration getCacheTtlInSec() {
    return Duration.ofSeconds(cacheTtlInSec);
  }

  public LdapSettings getLdapSettings() {
    return ldapSettings;
  }

  @Override
  void setLdapSettings(LdapSettings ldapSettings) {
    this.ldapSettings = ldapSettings;
  }

  @Override
  protected void validate() {
    if(name == null) {
      throw new ConfigMalformedException("'name' was not defined in ldap_authentication rule");
    }
    if(ldapSettings == null) {
      throw new ConfigMalformedException("Cannot find LDAP configuration with name [" + name + "]");
    }
  }
}

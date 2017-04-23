package org.elasticsearch.plugin.readonlyrest.settings;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.elasticsearch.plugin.readonlyrest.acl.blocks.rules.ConfigMalformedException;

import java.time.Duration;
import java.util.List;

public class LdapAuthorizationRuleSettings extends LdapSettingsDependent {

  @JsonProperty("name")
  private String name;

  @JsonProperty("groups")
  private List<String> groups = Lists.newArrayList();

  @JsonProperty("cache_ttl_in_sec")
  private int cacheTtlInSec = 0;

  private LdapSettings ldapSettings;

  @Override
  String getName() {
    return name;
  }

  public ImmutableList<String> getGroups() {
    return ImmutableList.copyOf(groups);
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
      throw new ConfigMalformedException("'name' was not defined in ldap_authorization rule");
    }
    if(ldapSettings == null) {
      throw new ConfigMalformedException("Cannot find LDAP configuration with name [" + name + "]");
    }
    if(groups.isEmpty()) {
      throw new ConfigMalformedException("No groups defined in LDAP rule [" + name + "]" );
    }
  }
}

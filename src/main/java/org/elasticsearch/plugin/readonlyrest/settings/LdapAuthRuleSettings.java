package org.elasticsearch.plugin.readonlyrest.settings;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.Lists;

import java.util.List;

public class LdapAuthRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "ldap_auth";

  @JsonProperty("name")
  private String name;

  @JsonProperty("groups")
  private List<String> groups = Lists.newArrayList();

  @JsonProperty("cache_ttl_in_sec")
  private int cacheTtlInSec = 0;

  private LdapSettings ldapSettings;

  private LdapAuthRuleSettings() {}

  private LdapAuthRuleSettings(String name, List<String> groups, int cacheTtlInSec, LdapSettings settings) {
    this.name = name;
    this.groups = groups;
    this.cacheTtlInSec = cacheTtlInSec;
    this.ldapSettings = settings;
  }

  public LdapAuthRuleSettings withLdapSettings(LdapSettings settings) {
    return new LdapAuthRuleSettings(name, groups, cacheTtlInSec, settings);
  }

  public String getName() {
    return name;
  }

//  public ImmutableList<String> getGroups() {
//    return ImmutableList.copyOf(groups);
//  }
//
//  public Duration getCacheTtlInSec() {
//    return Duration.ofSeconds(cacheTtlInSec);
//  }
//
//  public LdapSettings getLdapSettings() {
//    return ldapSettings;
//  }
//
//  @Override
//  void setLdapSettings(LdapSettings ldapSettings) {
//    this.ldapSettings = ldapSettings;
//  }
//
//  @Override
//  protected void validate() {
//    if(name == null) {
//      throw new ConfigMalformedException("'name' was not defined in ldap_auth rule");
//    }
//    if(ldapSettings == null) {
//      throw new ConfigMalformedException("Cannot find LDAP configuration with name [" + name + "]");
//    }
//    if(groups.isEmpty()) {
//      throw new ConfigMalformedException("No groups defined in LDAP rule [" + name + "]" );
//    }
//  }
}

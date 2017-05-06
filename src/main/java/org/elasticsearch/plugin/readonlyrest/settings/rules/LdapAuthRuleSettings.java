package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.settings.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.AuthenticationLdapSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.GroupsProviderLdapSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.LdapSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.LdapSettingsCollection;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

import java.time.Duration;
import java.util.Set;

public class LdapAuthRuleSettings implements RuleSettings, CacheSettings {

  public static final String ATTRIBUTE_NAME = "ldap_auth";

  private static final String LDAP_NAME = "name";
  private static final String GROUPS = "groups";
  private static final String CACHE = "cache_ttl_in_sec";

  private static final Duration DEFAULT_CACHE_TTL = Duration.ZERO;

  private final Set<String> groups;
  private final Duration cacheTtl;
  private final GroupsProviderLdapSettings ldapSettings;

  @SuppressWarnings("unchecked")
  public static LdapAuthRuleSettings from(RawSettings settings, LdapSettingsCollection ldapSettingsCollection) {
    String ldapName = settings.stringReq(LDAP_NAME);
    Set<String> groups = (Set<String>) settings.notEmptySetReq(GROUPS);
    return new LdapAuthRuleSettings(
        ldapSettingsCollection.get(ldapName),
        groups,
        settings.intOpt(CACHE).map(Duration::ofSeconds).orElse(DEFAULT_CACHE_TTL)
    );
  }

  private LdapAuthRuleSettings(LdapSettings settings, Set<String> groups, Duration cacheTtl) {
    if(!(settings instanceof GroupsProviderLdapSettings))
      throw new ConfigMalformedException("'" + ATTRIBUTE_NAME + "' rule cannot use simplified ldap client settings " +
          "(without '" + GroupsProviderLdapSettings.SEARCH_GROUPS +"' attribute defined)");
    this.groups = groups;
    this.cacheTtl = cacheTtl;
    this.ldapSettings = (GroupsProviderLdapSettings) settings;
  }

  @Override
  public Duration getCacheTtl() {
    return cacheTtl;
  }

  public LdapSettings getLdapSettings() {
    return ldapSettings;
  }

  public Set<String> getGroups() {
    return groups;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }
}

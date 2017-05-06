package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.LdapSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.LdapSettingsCollection;

import java.time.Duration;
import java.util.List;
import java.util.Set;

public class LdapAuthorizationRuleSettings  implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "ldap_authorization";

  private static final String LDAP_NAME = "name";
  private static final String GROUPS = "groups";
  private static final String CACHE = "cache_ttl_in_sec";

  private static final Duration DEFAULT_CACHE_TTL = Duration.ZERO;

  private final Set<String> groups;
  private final Duration cacheTtl;
  private final LdapSettings ldapSettings;

  @SuppressWarnings("unchecked")
  public static LdapAuthorizationRuleSettings from(RawSettings settings, LdapSettingsCollection ldapSettingsCollection) {
    String ldapName = settings.stringReq(LDAP_NAME);
    Set<String> groups = (Set<String>) settings.notEmptySetReq(GROUPS);
    return new LdapAuthorizationRuleSettings(
        ldapSettingsCollection.get(ldapName),
        groups,
        settings.intOpt(CACHE).map(Duration::ofSeconds).orElse(DEFAULT_CACHE_TTL)
    );
  }

  public static LdapAuthorizationRuleSettings from(LdapAuthRuleSettings settings) {
    return new LdapAuthorizationRuleSettings(settings.getLdapSettings(), settings.getGroups(), settings.getCacheTtl());
  }

  private LdapAuthorizationRuleSettings(LdapSettings settings, Set<String> groups, Duration cacheTtl) {
    this.groups = groups;
    this.cacheTtl = cacheTtl;
    this.ldapSettings = settings;
  }

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

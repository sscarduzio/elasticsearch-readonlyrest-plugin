package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.LdapSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.LdapSettingsCollection;

import java.time.Duration;
import java.util.List;

public class LdapAuthorizationRuleSettings  implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "ldap_authorization";

  private static final String LDAP_NAME = "name";
  private static final String GROUPS = "groups";
  private static final String CACHE = "cache_ttl_in_sec";

  private static final Duration DEFAULT_CACHE_TTL = Duration.ZERO;

  private final List<String> groups;
  private final Duration cacheTtl;
  private final LdapSettings ldapSettings;

  @SuppressWarnings("unchecked")
  public static LdapAuthorizationRuleSettings from(RawSettings settings, LdapSettingsCollection ldapSettingsCollection) {
    String ldapName = settings.stringReq(LDAP_NAME);
    List<String> groups = (List<String>) settings.notEmptyListReq(GROUPS);
    return new LdapAuthorizationRuleSettings(
        ldapSettingsCollection.get(ldapName),
        groups,
        settings.intOpt(CACHE).map(Duration::ofSeconds).orElse(DEFAULT_CACHE_TTL)
    );
  }

  private LdapAuthorizationRuleSettings(LdapSettings settings, List<String> groups, Duration cacheTtl) {
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

  public List<String> getGroups() {
    return groups;
  }
}

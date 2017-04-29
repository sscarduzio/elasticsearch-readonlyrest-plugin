package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.settings.LdapSettings;
import org.elasticsearch.plugin.readonlyrest.settings.LdapsSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;

import java.time.Duration;
import java.util.List;

public class LdapAuthRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "ldap_auth";

  private static final String LDAP_NAME = "name";
  private static final String GROUPS = "groups";
  private static final String CACHE = "cache_ttl_in_sec";

  private static final Duration DEFAULT_CACHE_TTL = Duration.ZERO;

  private final List<String> groups;
  private final Duration cacheTtl;
  private final LdapSettings ldapSettings;

  @SuppressWarnings("unchecked")
  public static LdapAuthRuleSettings from(RawSettings settings, LdapsSettings ldapsSettings) {
    String ldapName = settings.stringReq(LDAP_NAME);
    List<String> groups = (List<String>) settings.listReq(GROUPS);
    return new LdapAuthRuleSettings(
        ldapsSettings.get(ldapName),
        groups,
        settings.intOpt(CACHE).map(Duration::ofSeconds).orElse(DEFAULT_CACHE_TTL)
    );
  }

  private LdapAuthRuleSettings(LdapSettings settings, List<String> groups, Duration cacheTtl) {
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

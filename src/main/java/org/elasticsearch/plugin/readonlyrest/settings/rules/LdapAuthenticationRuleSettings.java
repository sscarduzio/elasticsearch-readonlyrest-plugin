package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.LdapSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.LdapSettingsCollection;

import java.time.Duration;

public class LdapAuthenticationRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "ldap_authentication";

  private static final String LDAP_NAME = "name";
  private static final String CACHE = "cache_ttl_in_sec";

  private static final Duration DEFAULT_CACHE_TTL = Duration.ZERO;

  private final Duration cacheTtl;
  private final LdapSettings ldapSettings;

  @SuppressWarnings("unchecked")
  public static LdapAuthenticationRuleSettings from(RawSettings settings, LdapSettingsCollection ldapSettingsCollection) {
    String ldapName = settings.stringReq(LDAP_NAME);
    return new LdapAuthenticationRuleSettings(
        ldapSettingsCollection.get(ldapName),
        settings.intOpt(CACHE).map(Duration::ofSeconds).orElse(DEFAULT_CACHE_TTL)
    );
  }

  public static LdapAuthenticationRuleSettings from(String ldapName, LdapSettingsCollection ldapSettingsCollection) {
    return new LdapAuthenticationRuleSettings(ldapSettingsCollection.get(ldapName), DEFAULT_CACHE_TTL);
  }

  private LdapAuthenticationRuleSettings(LdapSettings settings, Duration cacheTtl) {
    this.cacheTtl = cacheTtl;
    this.ldapSettings = settings;
  }

  public Duration getCacheTtl() {
    return cacheTtl;
  }

  public LdapSettings getLdapSettings() {
    return ldapSettings;
  }

}

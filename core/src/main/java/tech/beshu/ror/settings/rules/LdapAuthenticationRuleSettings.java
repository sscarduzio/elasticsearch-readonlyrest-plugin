/*
 *    This file is part of ReadonlyREST.
 *
 *    ReadonlyREST is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU General Public License as published by
 *    the Free Software Foundation, either version 3 of the License, or
 *    (at your option) any later version.
 *
 *    ReadonlyREST is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU General Public License for more details.
 *
 *    You should have received a copy of the GNU General Public License
 *    along with ReadonlyREST.  If not, see http://www.gnu.org/licenses/
 */
package tech.beshu.ror.settings.rules;

import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.settings.AuthKeyProviderSettings;
import tech.beshu.ror.settings.RuleSettings;
import tech.beshu.ror.settings.definitions.AuthenticationLdapSettings;
import tech.beshu.ror.settings.definitions.LdapSettings;
import tech.beshu.ror.settings.definitions.LdapSettingsCollection;

import java.time.Duration;

public class LdapAuthenticationRuleSettings implements RuleSettings, CacheSettings, AuthKeyProviderSettings {

  public static final String ATTRIBUTE_NAME = "ldap_authentication";

  private static final String LDAP_NAME = "name";
  private static final String CACHE = "cache_ttl_in_sec";

  private static final Duration DEFAULT_CACHE_TTL = Duration.ZERO;

  private final Duration cacheTtl;
  private final AuthenticationLdapSettings ldapSettings;

  private LdapAuthenticationRuleSettings(LdapSettings settings, Duration cacheTtl) {
    this.cacheTtl = cacheTtl;
    this.ldapSettings = (AuthenticationLdapSettings) settings;
  }

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

  public static LdapAuthenticationRuleSettings from(LdapAuthRuleSettings settings) {
    return new LdapAuthenticationRuleSettings(settings.getLdapSettings(), settings.getCacheTtl());
  }

  @Override
  public Duration getCacheTtl() {
    return cacheTtl;
  }

  public AuthenticationLdapSettings getLdapSettings() {
    return ldapSettings;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }

}

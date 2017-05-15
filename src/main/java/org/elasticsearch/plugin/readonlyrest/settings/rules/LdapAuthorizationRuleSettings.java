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
package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.settings.SettingsMalformedException;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.GroupsProviderLdapSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.LdapSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.LdapSettingsCollection;

import java.time.Duration;
import java.util.Set;

public class LdapAuthorizationRuleSettings implements RuleSettings, CacheSettings {

  public static final String ATTRIBUTE_NAME = "ldap_authorization";

  private static final String LDAP_NAME = "name";
  private static final String GROUPS = "groups";
  private static final String CACHE = "cache_ttl_in_sec";

  private static final Duration DEFAULT_CACHE_TTL = Duration.ZERO;

  private final Set<String> groups;
  private final Duration cacheTtl;
  private final GroupsProviderLdapSettings ldapSettings;

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
    if (!(settings instanceof GroupsProviderLdapSettings))
      throw new SettingsMalformedException("'" + ATTRIBUTE_NAME + "' rule cannot use simplified ldap client settings " +
          "(without '" + GroupsProviderLdapSettings.SEARCH_GROUPS + "' attribute defined)");
    this.groups = groups;
    this.cacheTtl = cacheTtl;
    this.ldapSettings = (GroupsProviderLdapSettings) settings;
  }

  @Override
  public Duration getCacheTtl() {
    return cacheTtl;
  }

  public GroupsProviderLdapSettings getLdapSettings() {
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

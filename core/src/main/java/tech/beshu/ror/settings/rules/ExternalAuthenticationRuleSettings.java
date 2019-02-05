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
import tech.beshu.ror.settings.RuleSettings;
import tech.beshu.ror.settings.definitions.ExternalAuthenticationServiceSettings;
import tech.beshu.ror.settings.definitions.ExternalAuthenticationServiceSettingsCollection;

import java.time.Duration;

public class ExternalAuthenticationRuleSettings implements RuleSettings, CacheSettings {

  public static final String ATTRIBUTE_NAME = "external_authentication";

  private static final String SERVICE = "service";
  private static final String CACHE = "cache_ttl_in_sec";

  private static final Duration DEFAULT_CACHE_TTL = Duration.ZERO;

  private final ExternalAuthenticationServiceSettings externalAuthenticationServiceSettings;
  private final Duration cacheTtl;

  private ExternalAuthenticationRuleSettings(ExternalAuthenticationServiceSettings externalAuthenticationServiceSettings,
                                             Duration cacheTtl) {
    this.externalAuthenticationServiceSettings = externalAuthenticationServiceSettings;
    this.cacheTtl = cacheTtl;
  }

  @SuppressWarnings("unchecked")
  public static ExternalAuthenticationRuleSettings from(RawSettings settings,
                                                        ExternalAuthenticationServiceSettingsCollection externalAuthenticationServiceSettingsCollection) {
    return new ExternalAuthenticationRuleSettings(
      externalAuthenticationServiceSettingsCollection.get(settings.stringReq(SERVICE)),
      settings.intOpt(CACHE).map(Duration::ofSeconds).orElse(DEFAULT_CACHE_TTL)
    );
  }

  public static ExternalAuthenticationRuleSettings from(String serviceName,
                                                        ExternalAuthenticationServiceSettingsCollection externalAuthenticationServiceSettingsCollection) {
    return new ExternalAuthenticationRuleSettings(
      externalAuthenticationServiceSettingsCollection.get(serviceName),
      DEFAULT_CACHE_TTL
    );
  }

  public ExternalAuthenticationServiceSettings getExternalAuthenticationServiceSettings() {
    return externalAuthenticationServiceSettings;
  }

  @Override
  public Duration getCacheTtl() {
    return cacheTtl;
  }

  @Override
  public String getName() {
    return ATTRIBUTE_NAME;
  }
}

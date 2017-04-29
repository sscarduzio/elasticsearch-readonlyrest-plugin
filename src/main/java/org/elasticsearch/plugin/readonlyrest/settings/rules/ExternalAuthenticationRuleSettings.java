package org.elasticsearch.plugin.readonlyrest.settings.rules;

import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.RuleSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.ExternalAuthenticationServiceSettings;
import org.elasticsearch.plugin.readonlyrest.settings.definitions.ExternalAuthenticationServiceSettingsCollection;

import java.time.Duration;

public class ExternalAuthenticationRuleSettings implements RuleSettings {

  public static final String ATTRIBUTE_NAME = "external_authentication";

  private static final String SERVICE = "service";
  private static final String CACHE = "cache_ttl_in_sec";

  private static final Duration DEFAULT_CACHE_TTL = Duration.ZERO;

  private final ExternalAuthenticationServiceSettings externalAuthenticationServiceSettings;
  private final Duration cacheTtl;

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
        DEFAULT_CACHE_TTL);
  }

  private ExternalAuthenticationRuleSettings(ExternalAuthenticationServiceSettings externalAuthenticationServiceSettings,
                                             Duration cacheTtl) {
    this.externalAuthenticationServiceSettings = externalAuthenticationServiceSettings;
    this.cacheTtl = cacheTtl;
  }

  public ExternalAuthenticationServiceSettings getExternalAuthenticationServiceSettings() {
    return externalAuthenticationServiceSettings;
  }

  public Duration getCacheTtl() {
    return cacheTtl;
  }
}

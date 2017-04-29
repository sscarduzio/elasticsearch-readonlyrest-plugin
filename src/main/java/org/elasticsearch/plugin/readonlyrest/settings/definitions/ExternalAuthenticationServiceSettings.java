package org.elasticsearch.plugin.readonlyrest.settings.definitions;

import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;

import java.net.URI;
import java.time.Duration;

public class ExternalAuthenticationServiceSettings {

  private static final String NAME = "name";
  private static final String ENDPOINT = "authentication_endpoint";
  private static final String SUCCESS_STATUS_CODE = "success_status_code";
  private static final String CACHE = "cache_ttl_in_sec";

  private static final int DEFAULT_SUCCESS_STATUS_CODE = 204;
  private static final Duration DEFAULT_CACHE_TTL = Duration.ZERO;

  private final String name;
  private final URI endpoint;
  private final int successStatusCode;
  private final Duration cacheTtl;

  public ExternalAuthenticationServiceSettings(RawSettings settings) {
    this.name = settings.stringReq(NAME);
    this.endpoint = settings.uriReq(ENDPOINT);
    this.successStatusCode = settings.intOpt(SUCCESS_STATUS_CODE).orElse(DEFAULT_SUCCESS_STATUS_CODE);
    this.cacheTtl = settings.intOpt(CACHE).map(Duration::ofSeconds).orElse(DEFAULT_CACHE_TTL);
  }

  public String getName() {
    return name;
  }

  public URI getEndpoint() {
    return endpoint;
  }

  public int getSuccessStatusCode() {
    return successStatusCode;
  }

  public Duration getCacheTtl() {
    return cacheTtl;
  }
}

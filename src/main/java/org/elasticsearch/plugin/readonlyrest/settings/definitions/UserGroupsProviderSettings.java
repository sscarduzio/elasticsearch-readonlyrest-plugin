package org.elasticsearch.plugin.readonlyrest.settings.definitions;

import org.elasticsearch.plugin.readonlyrest.settings.ConfigMalformedException;
import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.CacheSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.NamedSettings;

import java.net.URI;
import java.time.Duration;

public class UserGroupsProviderSettings implements CacheSettings, NamedSettings {

  public enum TokenPassingMethod {
    QUERY, HEADER
  }

  private static final String NAME = "name";
  private static final String ENDPOINT = "groups_endpoint";
  private static final String AUTH_TOKEN_NAME = "auth_token_name";
  private static final String PASSED_AS = "auth_token_passed_as";
  private static final String JSON_PATH = "response_groups_json_path";
  private static final String CACHE = "cache_ttl_in_sec";

  private static final Duration DEFAULT_CACHE_TTL = Duration.ZERO;

  private final String name;
  private final URI endpoint;
  private final String authTokenName;
  private final TokenPassingMethod authTokenPassedMethod;
  private final String responseGroupsJsonPath;
  private final Duration cacheTtl;

  public UserGroupsProviderSettings(RawSettings settings) {
    this.name = settings.stringReq(NAME);
    this.endpoint = settings.uriReq(ENDPOINT);
    this.authTokenName = settings.stringReq(AUTH_TOKEN_NAME);
    this.authTokenPassedMethod = tokenPassingMethodFromString(settings.stringReq(PASSED_AS));
    this.responseGroupsJsonPath = settings.stringReq(JSON_PATH);
    this.cacheTtl = settings.intOpt(CACHE).map(Duration::ofSeconds).orElse(DEFAULT_CACHE_TTL);
  }

  @Override
  public String getName() {
    return name;
  }

  public URI getEndpoint() {
    return endpoint;
  }

  public String getAuthTokenName() {
    return authTokenName;
  }

  public TokenPassingMethod getAuthTokenPassedMethod() {
    return authTokenPassedMethod;
  }

  public String getResponseGroupsJsonPath() {
    return responseGroupsJsonPath;
  }

  @Override
  public Duration getCacheTtl() {
    return cacheTtl;
  }

  private TokenPassingMethod tokenPassingMethodFromString(String value) {
    switch (value) {
      case "HEADER":
        return TokenPassingMethod.HEADER;
      case "QUERY_PARAM":
        return TokenPassingMethod.QUERY;
        default:
          throw new ConfigMalformedException("Unknown value '" + value + "' of '" + PASSED_AS + "' attribute");
    }
  }
}

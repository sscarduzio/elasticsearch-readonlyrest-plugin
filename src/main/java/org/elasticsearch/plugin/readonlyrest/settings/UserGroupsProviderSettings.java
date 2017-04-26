package org.elasticsearch.plugin.readonlyrest.settings;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.net.URI;
import java.time.Duration;

public class UserGroupsProviderSettings extends Settings {

  public enum TokenPassingMethod {
    QUERY, HEADER
  }

  @JsonProperty("name")
  private String name;

  @JsonProperty("groups_endpoint")
  private URI endpoint;

  @JsonProperty("auth_token_name")
  private String authTokenName;

  @JsonProperty("auth_token_passed_as")
  private TokenPassingMethod authTokenPassedMethod;

  @JsonProperty("response_groups_json_path")
  private String responseGroupsJsonPath;

  @JsonProperty("cache_ttl_in_sec")
  private int cacheTtlInSec = 0;

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

  public Duration getCacheTtlInSec() {
    return Duration.ofSeconds(cacheTtlInSec);
  }

  @Override
  protected void validate() {
    if(name == null) throwNotPresentMalformedException("name");
    if(endpoint == null) throwNotPresentMalformedException("groups_endpoint");
    if(authTokenName == null) throwNotPresentMalformedException("auth_token_name");
    if(authTokenPassedMethod == null) throwNotPresentMalformedException("auth_token_passed_as");
    if(responseGroupsJsonPath == null) throwNotPresentMalformedException("response_groups_json_path");
  }

  private void throwNotPresentMalformedException(String elemName) {
    throw new ConfigMalformedException("'" + elemName + "' was not defined in one of user_groups_providers element");
  }
}

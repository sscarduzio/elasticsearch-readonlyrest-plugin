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

package tech.beshu.ror.settings.definitions;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import tech.beshu.ror.commons.settings.RawSettings;
import tech.beshu.ror.commons.settings.SettingsMalformedException;
import tech.beshu.ror.httpclient.HttpMethod;
import tech.beshu.ror.settings.rules.CacheSettings;
import tech.beshu.ror.settings.rules.NamedSettings;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class UserGroupsProviderSettings implements CacheSettings, NamedSettings {

  private static final String NAME = "name";
  private static final String ENDPOINT = "groups_endpoint";
  private static final String AUTH_TOKEN_NAME = "auth_token_name";
  private static final String PASSED_AS = "auth_token_passed_as";
  private static final String JSON_PATH = "response_groups_json_path";
  private static final String CACHE = "cache_ttl_in_sec";
  private static final String DEFAULT_QUERY_PARAMS = "default_query_parameters";
  private static final String DEFAULT_HEADERS = "default_headers";
  private static final Duration DEFAULT_CACHE_TTL = Duration.ZERO;
  private static final String HTTP_METHOD = "http_method";
  private final String name;
  private final URI endpoint;
  private final String authTokenName;
  private final TokenPassingMethod authTokenPassedMethod;
  private final String responseGroupsJsonPath;
  private final Duration cacheTtl;
  private final ImmutableMap<String, String> defaultHeaders;
  private final ImmutableMap<String, String> defaultQueryParameters;
  private final HttpMethod method;
  private final Map<String, Set<String>> user2availGroups = Maps.newHashMap();
  private Function<LinkedHashMap<String, Object>, ImmutableMap<String, String>> toMap = (map) -> {
    Map<String, String> tempMap = new HashMap<>();
    map.entrySet().forEach(e -> tempMap.put(e.getKey(), String.valueOf(e.getValue())));
    return ImmutableMap.copyOf(tempMap);
  };

  public UserGroupsProviderSettings(RawSettings settings) {
    this.name = settings.stringReq(NAME);
    this.endpoint = settings.uriReq(ENDPOINT);
    this.authTokenName = settings.stringReq(AUTH_TOKEN_NAME);
    this.authTokenPassedMethod = tokenPassingMethodFromString(settings.stringReq(PASSED_AS));
    this.responseGroupsJsonPath = settings.stringReq(JSON_PATH);
    this.cacheTtl = settings.intOpt(CACHE).map(Duration::ofSeconds).orElse(DEFAULT_CACHE_TTL);
    this.defaultHeaders = settings.stringOpt(DEFAULT_HEADERS).isPresent() ? toMap.apply(
        (LinkedHashMap) settings.asMap().get(DEFAULT_HEADERS)) : ImmutableMap.<String, String>of();
    this.defaultQueryParameters = settings.stringOpt(DEFAULT_HEADERS).isPresent() ? toMap.apply(
        (LinkedHashMap) settings.asMap().get(DEFAULT_QUERY_PARAMS)) : ImmutableMap.<String, String>of();
    this.method = settings.opt(HTTP_METHOD).isPresent() ? httpMethodFromString(settings.stringReq(HTTP_METHOD)) :
        HttpMethod.GET;
  }

  public Map<String, Set<String>> getUser2availGroups() {
    return user2availGroups;
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
        throw new SettingsMalformedException("Unknown value '" + value + "' of '" + PASSED_AS + "' attribute");
    }
  }

  private HttpMethod httpMethodFromString(String method) {
    return method.equalsIgnoreCase("post") ? HttpMethod.POST : HttpMethod.GET;
  }

  public ImmutableMap<String, String> getDefaultHeaders() {
    return defaultHeaders;
  }

  public ImmutableMap<String, String> getDefaultQueryParameters() {
    return defaultQueryParameters;
  }

  public HttpMethod getMethod() {
    return method;
  }

  public enum TokenPassingMethod {
    QUERY, HEADER
  }
}

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
package org.elasticsearch.plugin.readonlyrest.settings.definitions;

import org.elasticsearch.plugin.readonlyrest.settings.RawSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.CacheSettings;
import org.elasticsearch.plugin.readonlyrest.settings.rules.NamedSettings;

import java.net.URI;
import java.time.Duration;

public class ExternalAuthenticationServiceSettings implements CacheSettings, NamedSettings {

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

  @Override
  public String getName() {
    return name;
  }

  public URI getEndpoint() {
    return endpoint;
  }

  public int getSuccessStatusCode() {
    return successStatusCode;
  }

  @Override
  public Duration getCacheTtl() {
    return cacheTtl;
  }
}

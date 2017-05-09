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
package org.elasticsearch.plugin.readonlyrest.httpclient;

import com.google.common.collect.Maps;
import org.elasticsearch.plugin.readonlyrest.acl.domain.HttpMethod;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

public class HttpRequest {

  private final HttpMethod method;
  private final URI url;
  private final Map<String, String> queryParams;
  private final Map<String, String> headers;
  private final Optional<String> body;

  public static HttpRequest get(URI url, Map<String, String> queryParams, Map<String, String> headers) {
    return new HttpRequest(HttpMethod.GET, url, queryParams, headers, Optional.empty());
  }
  public static HttpRequest get(URI url, Map<String, String> headers) {
    return get(url, Maps.newHashMap(), headers);
  }

  public HttpRequest(HttpMethod method, URI url, Map<String, String> queryParams, Map<String, String> headers, Optional<String> body) {
    this.method = method;
    this.url = url;
    this.queryParams = queryParams;
    this.headers = headers;
    this.body = body;
  }

  public HttpMethod getMethod() {
    return method;
  }

  public URI getUrl() {
    return url;
  }

  public Map<String, String> getQueryParams() {
    return queryParams;
  }

  public Map<String, String> getHeaders() {
    return headers;
  }

  public Optional<String> getBody() {
    return body;
  }
}
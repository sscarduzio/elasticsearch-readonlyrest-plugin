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
import javafx.beans.binding.StringBinding;
import org.elasticsearch.plugin.readonlyrest.acl.domain.HttpMethod;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class RRHttpRequest {

  private final HttpMethod method;
  private final URI url;
  private final Map<String, String> queryParams;
  private final Map<String, String> headers;
  private final Optional<String> body;
  private final String id;

  public static RRHttpRequest get(URI url, Map<String, String> queryParams, Map<String, String> headers) {
    return new RRHttpRequest(HttpMethod.GET, url, queryParams, headers, Optional.empty());
  }
  public static RRHttpRequest get(URI url, Map<String, String> headers) {
    return get(url, Maps.newHashMap(), headers);
  }

  public RRHttpRequest(HttpMethod method, URI url, Map<String, String> queryParams, Map<String, String> headers, Optional<String> body) {
    this.method = method;
    this.url = url;
    this.queryParams = queryParams;
    this.headers = headers;
    this.body = body;
    this.id = UUID.randomUUID().toString();
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

  @Override
  public String toString() {
    return new StringBuilder()
      .append("[").append(id).append("] ")
      .append(method).append(" ").append(url).toString();
  }
}
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
  public static HttpRequest get(URI url) {
    return get(url, Maps.newHashMap(), Maps.newHashMap());
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
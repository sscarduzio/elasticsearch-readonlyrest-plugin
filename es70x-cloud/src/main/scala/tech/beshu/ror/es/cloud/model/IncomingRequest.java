package tech.beshu.ror.es.cloud.model;

import java.util.List;
import java.util.Map;

public class IncomingRequest {
  private final String method;
  private final String uri;
  private final String body;
  private final Map<String, List<String>> headers;

  public String getMethod() {
    return method;
  }

  public String getUri() {
    return uri;
  }

  public String getBody() {
    return body;
  }

  public Map<String, List<String>> getHeaders() {
    return headers;
  }

  public IncomingRequest(String method, String uri, String body, Map<String, List<String>> headers) {
    this.method = method;
    this.uri = uri;
    this.body = body;
    this.headers = headers;
  }

  @Override
  public String toString() {
    return new StringBuilder().append(method).append(" ").append(uri).toString();
  }
}

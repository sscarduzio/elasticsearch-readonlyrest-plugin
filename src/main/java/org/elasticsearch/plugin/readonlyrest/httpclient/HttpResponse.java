package org.elasticsearch.plugin.readonlyrest.httpclient;

import java.io.InputStream;

public class HttpResponse {

  private final int statusCode;
  private final InputStream content;

  public HttpResponse(int statusCode, InputStream content) {
    this.statusCode = statusCode;
    this.content = content;
  }

  public int getStatusCode() {
    return statusCode;
  }

  public InputStream getContent() {
    return content;
  }
}

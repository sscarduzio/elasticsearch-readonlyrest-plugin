package org.elasticsearch.plugin.readonlyrest.httpclient;

public class HttpClientConfig {

  private final String host;
  private final int port;

  public HttpClientConfig(String host, int port) {
    this.host = host;
    this.port = port;
  }

  public String getHost() {
    return host;
  }

  public int getPort() {
    return port;
  }
}

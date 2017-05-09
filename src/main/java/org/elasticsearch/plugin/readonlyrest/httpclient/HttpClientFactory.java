package org.elasticsearch.plugin.readonlyrest.httpclient;

public interface HttpClientFactory {
  HttpClient create(HttpClientConfig config);
}

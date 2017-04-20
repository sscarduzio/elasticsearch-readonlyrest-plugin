package org.elasticsearch.plugin.readonlyrest.clients;

import com.google.common.collect.Maps;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.plugin.readonlyrest.utils.BasicAuthUtils;
import org.elasticsearch.plugin.readonlyrest.utils.CompletableFutureResponseListener;

import java.net.URI;
import java.util.concurrent.CompletableFuture;

public class ExternalAuthenticationServiceHttpClient implements ExternalAuthenticationServiceClient {

  private final URI endpoint;
  private final int successStatusCode;
  private final RestClient client;

  public ExternalAuthenticationServiceHttpClient(URI endpoint, int successStatusCode) {
    this.client = RestClient.builder(
        new HttpHost(
            endpoint.getHost(),
            endpoint.getPort()
        )
    ).build();
    this.endpoint = endpoint;
    this.successStatusCode = successStatusCode;
  }

  @Override
  public CompletableFuture<Boolean> authenticate(String user, String password) {
    final CompletableFuture<Boolean> promise = new CompletableFuture<>();
    client.performRequestAsync(
        "GET",
        endpoint.getPath(),
        Maps.newHashMap(),
        new CompletableFutureResponseListener<>(promise,
            response -> response.getStatusLine().getStatusCode() == successStatusCode),
        BasicAuthUtils.basicAuthHeader(user, password));
    return promise;
  }
}

package org.elasticsearch.plugin.readonlyrest.es53x;

import org.apache.http.Header;
import org.apache.http.HttpHost;
import org.apache.http.message.BasicHeader;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseListener;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.plugin.readonlyrest.acl.domain.HttpMethod;
import org.elasticsearch.plugin.readonlyrest.httpclient.HttpClient;
import org.elasticsearch.plugin.readonlyrest.httpclient.HttpClientConfig;
import org.elasticsearch.plugin.readonlyrest.httpclient.HttpClientFactory;
import org.elasticsearch.plugin.readonlyrest.httpclient.HttpRequest;
import org.elasticsearch.plugin.readonlyrest.httpclient.HttpResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public enum ESHttpClientFactory implements HttpClientFactory {
  INSTANCE;

  @Override
  public HttpClient create(HttpClientConfig config) {
    return new HttpClient() {

      private RestClient underlyingClient = RestClient.builder(new HttpHost(config.getHost(), config.getPort())).build();

      @Override
      public CompletableFuture<HttpResponse> send(HttpRequest request) {
        CompletableFuture<HttpResponse> promise = new CompletableFuture<>();
        underlyingClient.performRequestAsync(
            httpMethodToString(request.getMethod()),
            request.getUrl().toString(),
            request.getQueryParams(),
            new CompletableFutureResponseListener(promise),
            toHeadersList(request.getHeaders()).toArray(new Header[0])
        );
        return promise;
      }
    };
  }

  private String httpMethodToString(HttpMethod method) {
    switch (method) {
      case GET:
        return "GET";
      case POST:
        return "POST";
      case PUT:
        return "PUT";
      case DELETE:
        return "DELETE";
      case OPTIONS:
        return "OPTIONS";
      case HEAD:
        return "HEAD";
      default:
        throw new IllegalArgumentException();
    }
  }

  private List<Header> toHeadersList(Map<String, String> headersMap) {
    return headersMap.entrySet().stream()
        .map(entity -> new BasicHeader(entity.getKey(), entity.getValue()))
        .collect(Collectors.toList());
  }

  public static class CompletableFutureResponseListener implements ResponseListener {

    private final CompletableFuture<HttpResponse> promise;

    CompletableFutureResponseListener(CompletableFuture<HttpResponse> promise) {
      this.promise = promise;
    }

    @Override
    public void onSuccess(Response response) {
      try {
        promise.complete(from(response));
      } catch (IOException e) {
        promise.completeExceptionally(e);
      }
    }

    @Override
    public void onFailure(Exception exception) {
      promise.completeExceptionally(exception);
    }

    private HttpResponse from(Response response) throws IOException {
      return new HttpResponse(response.getStatusLine().getStatusCode(), response.getEntity().getContent());
    }
  }

}

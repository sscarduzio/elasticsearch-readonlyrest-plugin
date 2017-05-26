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
package org.elasticsearch.plugin.readonlyrest.es;

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
            request.getUrl().getPath(),
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
      return new HttpResponse(response.getStatusLine().getStatusCode(), () -> {
        try {
          return response.getEntity().getContent();
        } catch (IOException e) {
          throw new RuntimeException("Cannot read content");
        }
      });
    }
  }

}

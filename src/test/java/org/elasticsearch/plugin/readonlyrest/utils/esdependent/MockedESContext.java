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
package org.elasticsearch.plugin.readonlyrest.utils.esdependent;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.httpclient.HttpClient;
import org.elasticsearch.plugin.readonlyrest.httpclient.HttpClientFactory;
import org.elasticsearch.plugin.readonlyrest.httpclient.HttpRequest;
import org.elasticsearch.plugin.readonlyrest.httpclient.HttpResponse;
import org.elasticsearch.plugin.readonlyrest.utils.httpclient.RestClient;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.CompletableFuture;

public class MockedESContext implements ESContext {

  public static final ESContext INSTANCE = new MockedESContext();

  @Override
  public Logger logger(Class<?> clazz) {
    return LogManager.getLogger(clazz);
  }

  @Override
  public RuntimeException rorException(String message) {
    return new RuntimeException(message);
  }

  @Override
  public HttpClientFactory httpClientFactory() {
    return config -> new HttpClientAdapter();
  }

  private static class HttpClientAdapter implements HttpClient {

    @Override
    public CompletableFuture<HttpResponse> send(HttpRequest request) {
      RestClient client = new RestClient(request.getUrl().getHost(), request.getUrl().getPort());
      return CompletableFuture
          .supplyAsync(() -> {
            try {
              return client.execute(toUriRequest(request));
            } catch (Exception e) {
              throw new RuntimeException("execution exception", e);
            }
          })
          .thenApply(this::toResponse);
    }

    private HttpUriRequest toUriRequest(HttpRequest request) throws URISyntaxException {
      URIBuilder uriBuilder = new URIBuilder(request.getUrl());
      request.getQueryParams().forEach(uriBuilder::setParameter);
      URI uri = uriBuilder.build();
      switch (request.getMethod()) {
        case GET:
          HttpGet req = new HttpGet(uri);
          request.getHeaders().forEach(req::setHeader);
          return req;
        case POST:
          HttpPost reqPost = new HttpPost(uri);
          request.getHeaders().forEach(reqPost::setHeader);
          request.getBody().ifPresent(body -> {
            try {
              reqPost.setEntity(new StringEntity(body));
            } catch (UnsupportedEncodingException e) {
              throw new RuntimeException("Unsupported encoding", e);
            }
          });
          return reqPost;
        default:
          throw new RuntimeException("not implemented yet");
      }
    }

    private HttpResponse toResponse(org.apache.http.HttpResponse resp) {
      return new HttpResponse(resp.getStatusLine().getStatusCode(), () -> {
        try {
          return resp.getEntity().getContent();
        } catch (IOException e) {
          throw new RuntimeException("Getting content failed", e);
        }
      });
    }
  }
}

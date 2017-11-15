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
package tech.beshu.ror.mocks;

import org.apache.commons.lang.NotImplementedException;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.mockito.Mockito;
import tech.beshu.ror.commons.settings.BasicSettings;
import tech.beshu.ror.commons.shims.es.AbstractESContext;
import tech.beshu.ror.commons.shims.es.ESContext;
import tech.beshu.ror.commons.shims.es.ESVersion;
import tech.beshu.ror.commons.shims.es.LoggerShim;
import tech.beshu.ror.httpclient.HttpClient;
import tech.beshu.ror.httpclient.RRHttpRequest;
import tech.beshu.ror.httpclient.RRHttpResponse;
import tech.beshu.ror.utils.httpclient.RestClient;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public class MockedESContext extends AbstractESContext {

  public static final ESContext INSTANCE = new MockedESContext();
  private final String customSerializer;

  public MockedESContext() {
    this(null);
  }

  public MockedESContext(String customSerializer) {
    this.customSerializer = customSerializer;
  }

  @Override
  public LoggerShim mkLogger(Class<?> clazz) {
    Logger l = LogManager.getLogger(clazz.getSimpleName());
    return new LoggerShim() {

      @Override
      public void trace(String message) {
        l.trace(message);
      }

      @Override
      public void info(String message) {
        l.info(message);
      }

      @Override
      public void debug(String message) {
        l.debug(message);
      }

      @Override
      public void warn(String message) {
        l.warn(message);
      }

      @Override
      public void warn(String message, Throwable t) {
        l.warn(message);
        t.printStackTrace();
      }

      @Override
      public void error(String message, Throwable t) {
        l.error(message);
        t.printStackTrace();
      }

      @Override
      public void error(String message) {
        l.error(message);
      }

      @Override
      public boolean isDebugEnabled() {
        return l.isDebugEnabled();
      }
    };
  }

  @Override
  public RuntimeException rorException(String message) {
    return new RuntimeException(message);
  }

  @Override
  public void submit(String indexName, String documentId, String jsonRecord) {
    throw new NotImplementedException();
  }

  public HttpClient mkHttpClient() {
    return new HttpClientAdapter();
  }

  @Override
  public ESVersion getVersion() {
    return ESVersion.V_5_4_0;
  }

  @Override
  public BasicSettings getSettings() {

    BasicSettings bs = Mockito.mock(BasicSettings.class);
    Mockito.when(bs.getCustomAuditSerializer()).thenReturn(Optional.ofNullable(customSerializer));
    return bs;
  }

  private static class HttpClientAdapter implements HttpClient {

    @Override
    public CompletableFuture<RRHttpResponse> send(RRHttpRequest request) {
      RestClient client = new RestClient(true, request.getUrl().getHost(), request.getUrl().getPort());
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

    private HttpUriRequest toUriRequest(RRHttpRequest request) throws URISyntaxException {
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

    private RRHttpResponse toResponse(org.apache.http.HttpResponse resp) {
      return new RRHttpResponse(resp.getStatusLine().getStatusCode(), () -> {
        try {
          return resp.getEntity().getContent();
        } catch (IOException e) {
          throw new RuntimeException("Getting content failed", e);
        }
      });
    }
  }
}

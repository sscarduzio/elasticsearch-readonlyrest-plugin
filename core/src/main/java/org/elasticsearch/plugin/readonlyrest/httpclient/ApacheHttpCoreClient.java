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

package org.elasticsearch.plugin.readonlyrest.httpclient;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;
import org.apache.http.message.BasicNameValuePair;
import org.elasticsearch.plugin.readonlyrest.ESContext;
import org.elasticsearch.plugin.readonlyrest.LoggerShim;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;


/**
 * Created by sscarduzio on 03/07/2017.
 */
public class ApacheHttpCoreClient implements HttpClient {
  private final CloseableHttpAsyncClient hcHttpClient;
  private final LoggerShim logger;
  private final ESContext context;

  public ApacheHttpCoreClient(ESContext esContext) {
    this.hcHttpClient = HttpAsyncClients.createDefault();
    this.hcHttpClient.start();
    this.logger = esContext.logger(getClass());
    this.context = esContext;
  }

  @Override
  public CompletableFuture<RRHttpResponse> send(RRHttpRequest request) {

    CompletableFuture<HttpResponse> promise = new CompletableFuture<>();

    URI uri;
    try {
      uri = new URIBuilder(request.getUrl().toASCIIString())
        .addParameters(
          request.getQueryParams().entrySet().stream()
            .map(e -> new BasicNameValuePair(e.getKey(), e.getValue()))
            .collect(Collectors.toList())
        ).build();
    } catch (URISyntaxException e) {
      throw context.rorException(e.getClass().getSimpleName() + ": " + e.getMessage());
    }

    final HttpGet hcRequest = new HttpGet(uri);
    request.getHeaders().entrySet().forEach(e -> hcRequest.addHeader(e.getKey(), e.getValue()));

    hcHttpClient.execute(hcRequest, new FutureCallback<HttpResponse>() {

      public void completed(final HttpResponse hcResponse) {
        int statusCode = hcResponse.getStatusLine().getStatusCode();
        logger.debug("HTTP REQ SUCCESS with status: " + statusCode + " " + request);
        promise.complete(hcResponse);
      }

      public void failed(final Exception ex) {
        logger.debug("HTTP REQ FAILED " + request);
        logger.info("HTTP client failed to connect: " + request + " reason: " + ex.getMessage());
        promise.completeExceptionally(ex);
      }

      public void cancelled() {
        promise.completeExceptionally(new RuntimeException("HTTP REQ CANCELLED: " + request));
      }
    });

    return promise.thenApply(hcResp -> new RRHttpResponse(hcResp.getStatusLine().getStatusCode(), () -> {
      try {
        return hcResp.getEntity().getContent();
      } catch (IOException e) {
        throw new RuntimeException("Cannot read content", e);
      }
    }));

  }

}

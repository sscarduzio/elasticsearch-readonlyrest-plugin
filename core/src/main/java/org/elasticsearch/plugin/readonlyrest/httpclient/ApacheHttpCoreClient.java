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
import org.apache.http.concurrent.FutureCallback;
import org.apache.http.impl.nio.client.CloseableHttpAsyncClient;
import org.apache.http.impl.nio.client.HttpAsyncClients;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;


/**
 * Created by sscarduzio on 03/07/2017.
 */
public class ApacheHttpCoreClient implements HttpClient {
  private final CloseableHttpAsyncClient hcHttpClient;

  public ApacheHttpCoreClient() {
    this.hcHttpClient = HttpAsyncClients.createDefault();
  }

  @Override
  public CompletableFuture<RRHttpResponse> send(RRHttpRequest request) {

    CompletableFuture<HttpResponse> promise = new CompletableFuture<>();

    final HttpGet hcRequest = new HttpGet(request.getUrl().toASCIIString());
    request.getHeaders().entrySet().forEach(e -> hcRequest.addHeader(e.getKey(),e.getValue()));

    hcHttpClient.execute(hcRequest, new FutureCallback<HttpResponse>() {

      public void completed(final HttpResponse hcResponse) {
        if (hcResponse.getStatusLine().getStatusCode() == 200) {
          promise.complete(hcResponse);
        }
      }

      public void failed(final Exception ex) {
        promise.completeExceptionally(ex);
      }

      public void cancelled() {
        promise.completeExceptionally(new RuntimeException("HC HTTP Request to " + request.getUrl() + "cancelled"));
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

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

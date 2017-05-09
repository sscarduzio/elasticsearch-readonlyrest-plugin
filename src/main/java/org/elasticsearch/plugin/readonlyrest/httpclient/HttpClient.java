package org.elasticsearch.plugin.readonlyrest.httpclient;

import java.util.concurrent.CompletableFuture;

public interface HttpClient {

  CompletableFuture<HttpResponse> send(HttpRequest request);
}

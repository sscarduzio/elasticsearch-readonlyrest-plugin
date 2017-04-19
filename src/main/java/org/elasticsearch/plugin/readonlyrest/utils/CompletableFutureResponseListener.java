package org.elasticsearch.plugin.readonlyrest.utils;

import org.elasticsearch.client.Response;
import org.elasticsearch.client.ResponseListener;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class CompletableFutureResponseListener<T> implements ResponseListener {

  private final CompletableFuture<T> promise;
  private final Function<Response, T> converter;

  public CompletableFutureResponseListener(CompletableFuture<T> promise,
                                           Function<Response, T> converter) {
    this.promise = promise;
    this.converter = converter;
  }

  @Override
  public void onSuccess(Response response) {
    promise.complete(converter.apply(response));
  }

  @Override
  public void onFailure(Exception exception) {
    promise.completeExceptionally(exception);
  }
}

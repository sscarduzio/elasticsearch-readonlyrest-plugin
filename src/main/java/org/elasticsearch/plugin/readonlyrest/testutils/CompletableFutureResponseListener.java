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
package org.elasticsearch.plugin.readonlyrest.testutils;

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

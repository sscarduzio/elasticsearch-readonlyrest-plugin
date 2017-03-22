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

package org.elasticsearch.plugin.readonlyrest.utils;

import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class FuturesSequencer {
  private FuturesSequencer() {
  }

  public static <A, B, C> CompletableFuture<C> runInSeqUntilConditionIsUndone(Iterator<A> iterator,
                                                                              Function<A, CompletableFuture<B>> asyncFunc,
                                                                              Function<B, Boolean> breakCondition,
                                                                              Function<B, C> breakReturn,
                                                                              Function<Void, C> noBreakReturn) {
    return runInSeqUntilConditionIsUndone(iterator, asyncFunc, (a, b) -> breakCondition.apply(b), breakReturn, noBreakReturn);
  }

  public static <A, B> CompletableFuture<B> runInSeqUntilConditionIsUndone(Iterator<A> iterator,
                                                                           Function<A, CompletableFuture<B>> asyncFunc,
                                                                           Function<B, Boolean> breakCondition,
                                                                           Function<Void, B> noBreakReturn) {
    return runInSeqUntilConditionIsUndone(iterator, asyncFunc, breakCondition, res -> res, noBreakReturn);
  }


  public static <A, B, C> CompletableFuture<C> runInSeqUntilConditionIsUndone(Iterator<A> iterator,
                                                                              Function<A, CompletableFuture<B>> asyncFunc,
                                                                              BiFunction<A, B, Boolean> breakCondition,
                                                                              Function<B, C> breakReturn,
                                                                              Function<Void, C> noBreakReturn) {
    if (iterator.hasNext()) {
      A value = iterator.next();
      return asyncFunc.apply(value)
        .thenCompose(result -> {
          if (breakCondition.apply(value, result)) {
            return CompletableFuture.completedFuture(breakReturn.apply(result));
          }
          else {
            return runInSeqUntilConditionIsUndone(iterator, asyncFunc, breakCondition, breakReturn, noBreakReturn);
          }
        });
    }
    else {
      return CompletableFuture.completedFuture(noBreakReturn.apply(null));
    }
  }
}

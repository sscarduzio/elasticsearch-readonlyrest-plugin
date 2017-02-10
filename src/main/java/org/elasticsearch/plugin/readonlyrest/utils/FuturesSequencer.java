package org.elasticsearch.plugin.readonlyrest.utils;

import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public final class FuturesSequencer {
    private FuturesSequencer() {}

    public static <A, B, C> CompletableFuture<C> runInSeqUntilConditionIsUndone(Iterator<A> iterator,
                                                                                Function<A, CompletableFuture<B>> asyncFunc,
                                                                                Function<B, Boolean> breakCondition,
                                                                                Function<B, C> breakReturn,
                                                                                Function<Void, C> noBreakReturn) {
        if (iterator.hasNext()) {
            return asyncFunc.apply(iterator.next())
                    .thenCompose(result -> {
                        if (breakCondition.apply(result)) {
                            return CompletableFuture.completedFuture(breakReturn.apply(result));
                        } else {
                            return runInSeqUntilConditionIsUndone(iterator, asyncFunc, breakCondition, breakReturn, noBreakReturn);
                        }
                    });
        } else {
            return CompletableFuture.completedFuture(noBreakReturn.apply(null));
        }
    }

    public static <A, B> CompletableFuture<B> runInSeqUntilConditionIsUndone(Iterator<A> iterator,
                                                                             Function<A, CompletableFuture<B>> asyncFunc,
                                                                             Function<B, Boolean> breakCondition,
                                                                             Function<Void, B> noBreakReturn) {
       return runInSeqUntilConditionIsUndone(iterator, asyncFunc, breakCondition, res -> res, noBreakReturn);
    }
}

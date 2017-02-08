package org.elasticsearch.plugin.readonlyrest.utils;

import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Iterator;
import java.util.function.BiFunction;
import java.util.function.Function;

public final class FuturesSequencer {
    private FuturesSequencer() {
    }

    public static <A, B> ListenableFuture<B> runInSeqUntilConditionIsUndone(Iterator<A> iterator,
                                                                            AsyncFunction<A, B> asyncFunc,
                                                                            Function<B, Boolean> breakCondition,
                                                                            Function<Void, B> noBreakReturn) {
        if (iterator.hasNext()) {
            ListenableFuture<B> asyncFuncResult;
            try {
                asyncFuncResult = asyncFunc.apply(iterator.next());
            } catch (Exception ex) {
                asyncFuncResult = Futures.immediateFailedFuture(ex);
            }
            return Futures.transformAsync(
                    asyncFuncResult,
                    result -> {
                        if (breakCondition.apply(result)) {
                            return Futures.immediateFuture(result);
                        } else {
                            return runInSeqUntilConditionIsUndone(iterator, asyncFunc, breakCondition, noBreakReturn);
                        }
                    });
        } else {
            return Futures.immediateFuture(noBreakReturn.apply(null));
        }
    }

    public static <A, B, C> ListenableFuture<C> runInSeqWithResult(Iterator<A> iterator,
                                                                   AsyncFunction<A, B> asyncFunc,
                                                                   BiFunction<B, C, C> combineResult,
                                                                   C resultAccumulator) {
        if (iterator.hasNext()) {
            ListenableFuture<B> asyncFuncResult;
            try {
                asyncFuncResult = asyncFunc.apply(iterator.next());
            } catch (Exception ex) {
                asyncFuncResult = Futures.immediateFailedFuture(ex);
            }
            return Futures.transformAsync(
                    asyncFuncResult,
                    result -> {
                        C stepResult = combineResult.apply(result, resultAccumulator);
                        return runInSeqWithResult(iterator, asyncFunc, combineResult, stepResult);
                    });
        } else {
            return Futures.immediateFuture(resultAccumulator);
        }
    }
}

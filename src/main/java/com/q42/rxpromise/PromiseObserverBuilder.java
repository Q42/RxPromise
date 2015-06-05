package com.q42.rxpromise;

import rx.functions.Action0;
import rx.functions.Action1;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by thijs on 04-06-15.
 */
public class PromiseObserverBuilder<T> {
    private final List<Action1<T>> successHandlers = new ArrayList<>(4);
    private final List<Action0> finallyHandlers = new ArrayList<>(4);
    private final List<RejectedHandler> errorHandlers = new ArrayList<>(4);

    public static <T> PromiseObserverBuilder<T> promiseObserver() {
        return new PromiseObserverBuilder<>();
    }

    public PromiseObserverBuilder<T> success(Action1<T> success) {
        this.successHandlers.add(success);
        return this;
    }

    public PromiseObserverBuilder<T> error(Action1<Throwable> error) {
        this.errorHandlers.add(new RejectedHandler(Throwable.class, error));
        return this;
    }

    @SuppressWarnings("unchecked")
    public <E extends Throwable> PromiseObserverBuilder<T> error(Class<E> throwableClass, Action1<E> error) {
        this.errorHandlers.add(new RejectedHandler(throwableClass, (Action1<Throwable>) error));
        return this;
    }

    public PromiseObserverBuilder<T> finallyDo(Action0 finallyDo) {
        this.finallyHandlers.add(finallyDo);
        return this;
    }

    public PromiseObserver<T> build() {
        return new PromiseObserver<T>() {
            @Override
            public void onFinally() {
                for (Action0 finallyHandler : finallyHandlers) {
                    finallyHandler.call();
                }
            }

            @Override
            public void onRejected(Throwable t) {
                for (RejectedHandler rejectedHandler : errorHandlers) {
                    if (rejectedHandler.throwableClass.isAssignableFrom(t.getClass())) {
                        rejectedHandler.action.call(t);
                    }
                }
            }

            @Override
            public void onFulfilled(T t) {
                for (Action1<T> successHandler : successHandlers) {
                    successHandler.call(t);
                }
            }
        };
    }

    class RejectedHandler {
        private final Class<? extends Throwable> throwableClass;
        private final Action1<Throwable> action;

        RejectedHandler(Class<? extends Throwable> throwableClass, Action1<Throwable> action) {
            this.throwableClass = throwableClass;
            this.action = action;
        }
    }
}

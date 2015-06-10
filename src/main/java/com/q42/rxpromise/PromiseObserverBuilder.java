package com.q42.rxpromise;

import rx.functions.Action0;
import rx.functions.Action1;

import java.util.ArrayList;
import java.util.List;

/**
 * A builder to create a {@link PromiseObserver} with some convenience methods, use with {@link Promise#then(PromiseObserverBuilder)}
 */
public class PromiseObserverBuilder<T> {
    private final List<Action1<T>> successHandlers = new ArrayList<Action1<T>>(4);
    private final List<Action0> finallyHandlers = new ArrayList<Action0>(4);
    private final List<RejectedHandler> errorHandlers = new ArrayList<RejectedHandler>(4);

    /**
     * Add a callback for when the {@link Promise} is fulfilled
     */
    public PromiseObserverBuilder<T> onSuccess(Action1<T> success) {
        this.successHandlers.add(success);
        return this;
    }

    /**
     * Add a callback for when the {@link Promise} is rejected
     */
    public PromiseObserverBuilder<T> onError(Action1<Throwable> error) {
        this.errorHandlers.add(new RejectedHandler(Throwable.class, error));
        return this;
    }

    /**
     * Add a callback for when the {@link Promise} is rejected
     * @param throwableClass The exception class (or subclasses) you want to attach the error callback to
     */
    @SuppressWarnings("unchecked")
    public <E extends Throwable> PromiseObserverBuilder<T> onError(Class<E> throwableClass, Action1<E> error) {
        this.errorHandlers.add(new RejectedHandler(throwableClass, (Action1<Throwable>) error));
        return this;
    }

    /**
     * Add a callback for when the {@link Promise} is either fulfilled or rejected
     */
    public PromiseObserverBuilder<T> onFinally(Action0 finallyDo) {
        this.finallyHandlers.add(finallyDo);
        return this;
    }

    /**
     * Build the {@link PromiseObserver}
     */
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

    private class RejectedHandler {
        private final Class<? extends Throwable> throwableClass;
        private final Action1<Throwable> action;

        RejectedHandler(Class<? extends Throwable> throwableClass, Action1<Throwable> action) {
            this.throwableClass = throwableClass;
            this.action = action;
        }
    }
}

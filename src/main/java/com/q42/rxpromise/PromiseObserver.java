package com.q42.rxpromise;

/**
 * An interface for receiving {@link Promise} lifecycle events with {@link Promise#then(PromiseObserver)}
 */
public interface PromiseObserver<T> {
    /**
     * Called when the {@link Promise} is fulfilled
     * @param value The fulfillment value
     */
    void onFulfilled(T value);

    /**
     * Called when the {@link Promise} is rejected
     * @param throwable The rejection reason
     */
    void onRejected(Throwable throwable);

    /**
     * Called when the {@link Promise} is either fulfilled or rejected
     */
    void onFinally();
}

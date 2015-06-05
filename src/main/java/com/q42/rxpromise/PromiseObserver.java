package com.q42.rxpromise;

/**
 * Created by thijs on 05-06-15.
 */
public interface PromiseObserver<T> {
    void onFulfilled(T value);
    void onRejected(Throwable throwable);
    void onFinally();
}

package com.q42.rxpromise;

import rx.Observer;
import rx.functions.Action0;
import rx.functions.Action1;

import java.util.HashMap;
import java.util.Map;

/**
 * Created by thijs on 04-06-15.
 */
public class ObserverBuilder<T> {
    private final Action1<T> success;
    private final Map<Class<? extends Throwable>, Action1<Throwable>> errorHandlers = new HashMap<>(4);
    private Action0 finallyDo;

    private ObserverBuilder(Action1<T> success) {
        this.success = success;
    }

    public static <T> ObserverBuilder<T> success(Action1<T> success) {
        return new ObserverBuilder<>(success);
    }

    public ObserverBuilder<T> error(Action1<Throwable> error) {
        this.errorHandlers.put(Throwable.class, error);
        return this;
    }

    @SuppressWarnings("unchecked")
    public <E extends Throwable> ObserverBuilder<T> error(Class<E> clazz, Action1<E> error) {
        this.errorHandlers.put(Throwable.class, (Action1<Throwable>) error);
        return this;
    }

    public ObserverBuilder<T> finallyDo(Action0 finallyDo) {
        this.finallyDo = finallyDo;
        return this;
    }

    public Observer<? super T> build() {
        return new Observer<T>() {
            @Override
            public void onCompleted() {
                finallyDo.call();
            }

            @Override
            public void onError(Throwable t) {
                for (Map.Entry<Class<? extends Throwable>, Action1<Throwable>> entry : errorHandlers.entrySet()) {
                    if (entry.getKey().isAssignableFrom(t.getClass())) {
                        entry.getValue().call(t);
                    }
                }
            }

            @Override
            public void onNext(T t) {
                success.call(t);
            }
        };
    }
}

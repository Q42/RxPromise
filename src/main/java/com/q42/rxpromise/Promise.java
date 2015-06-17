package com.q42.rxpromise;

import rx.*;
import rx.exceptions.CompositeException;
import rx.functions.*;
import rx.schedulers.Schedulers;
import rx.subjects.ReplaySubject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static java.util.Arrays.asList;
import static rx.Observable.combineLatest;
import static rx.Observable.merge;

/**
 * <p>
 *    A promise wrapper around RxJava's {@link Observable}. For simplified handling of Observables that emit a single value.
 *    A promise represents a future value (usually of an asynchronous operation).
 * </p>
 * <p>
 *    <strong>Semantics</strong><br>
 *    This promise is eager and is always cached:
 * </p>
 * <ul>
 *     <li><strong>Eager:</strong> The promise will start fulfilling it's value as soon as it's created.</li>
 *     <li><strong>Cached:</strong> The value will be redeemed from it's origin only once. All calls will wait until the promise is successfully fulfilled or rejected. All subsequent calls will immediately get the already fulfilled value (or exception).</li>
 * </ul>
 * <p>For example, if the promise is a future value from a HTTP API call, the API call will always be called once, regardless of the complexity of the promise chain.</p>
 * <p><strong>Example:</strong></p>
 * <pre>
 * Promise.async(Test::longRunningOperation)
 *         .map(integer -> integer * 10)
 *         .flatMap(Test::anotherLongRunningOperation)
 *         .onError(SomeException.class, someException -> log(someException))
 *         .onSuccess(o -> doSomeThingWith(o));
 * </pre>
 */
public class Promise<T> {
    /**
     * The default {@link Scheduler} that is used for {@link #async(Callable)}
     */
    public static Scheduler DEFAULT_ASYNC_SCHEDULER = Schedulers.io();

    /**
     * The default {@link Scheduler} that is used for callbacks
     */
    public static Scheduler DEFAULT_CALLBACKS_SCHEDULER = null;

    private final Observable<T> observable;

    private Promise(final Observable<T> observable) {
        final ReplaySubject<T> subject = ReplaySubject.create(1);
        observable.single().subscribe(subject);
        this.observable = applyObserveOnScheduler(subject, DEFAULT_CALLBACKS_SCHEDULER);
    }

    /**
     * Creates a new promise based on the observable, this must be an observable that emits a single item.
     * If the observable emits more than one item or no items the promise is rejected with an
     * {@code IllegalArgumentException} or {@code NoSuchElementException} respectively.
     */
    public static <T> Promise<T> from(Observable<T> observable) {
        return new Promise<T>(observable);
    }

    /**
     * Returns a promise that executes the specified {@link Callable} asynchronously on the {@link #DEFAULT_ASYNC_SCHEDULER}
     */
    public static <T> Promise<T> async(final Callable<T> callable) {
        return from(callable, DEFAULT_ASYNC_SCHEDULER);
    }

    /**
     * Returns a promise that executes the specified {@link Callable} on the specified {@link Scheduler}
     */
    public static <T> Promise<T> from(final Callable<T> callable, final Scheduler scheduler) {
        return from(Observable.create(new Observable.OnSubscribe<T>() {
            @Override
            public void call(Subscriber<? super T> subscriber) {
                try {
                    subscriber.onNext(callable.call());
                    subscriber.onCompleted();
                } catch (Throwable throwable) {
                    subscriber.onError(throwable);
                }
            }
        }).subscribeOn(scheduler));
    }

    /**
     * Converts a {@link Future} into a promise.
     * @param future the source {@link Future}
     * @param scheduler the {@link Scheduler} to wait for the Future on.
     */
    public static <T> Promise<T> promise(Future<? extends T> future, Scheduler scheduler) {
        return new Promise<T>(Observable.from(future, scheduler));
    }

    /**
     * Returns a promise that fulfills with the supplied value immediately.
     */
    public static <T> Promise<T> just(final T value) {
        return from(Observable.just(value));
    }

    /**
     * Returns a promise that will be rejected immediately with the supplied error.
     */
    public static <T> Promise<T> error(final Throwable throwable) {
        return from(Observable.<T>error(throwable));
    }

    /**
     * Given an array of promises, return a promise that is fulfilled when all the items in the array are fulfilled.
     * The promise's fulfillment value is a {@link List} with fulfillment values in the original order.
     * If any promise rejects, the returned promise is rejected immediately with the rejection reason.
     *
     * @param promises The {@link List} of promises
     * @return A promise that combines all values of given promises into a {@link List}
     */
    @SafeVarargs
    public static <T> Promise<List<T>> all(Promise<T>... promises) {
        return all(asList(promises));
    }

    /**
     * Given an {@link Iterable} of promises, return a promise that is fulfilled when all the items in the {@link Iterable} are fulfilled.
     * The promise's fulfillment value is a {@link List} with fulfillment values in the original order.
     * If any promise rejects, the returned promise is rejected immediately with the rejection reason.
     *
     * @param promises The {@link List} of promises
     * @return A promise that combines all values of given promises into a {@link List}
     */
    @SuppressWarnings("unchecked")
    public static <T> Promise<List<T>> all(Iterable<Promise<T>> promises) {
        List<Observable<T>> observables = coerceToList(promises, Promise.<T>coerceToObservable());

        if (observables.isEmpty()) {
            return just(Collections.<T>emptyList());
        }

        return from(combineLatest(observables, new FuncN<List<T>>() {
            @Override
            public List<T> call(Object... args) {
                return asList((T[]) args);
            }
        }));
    }

    /**
     * Initiate a competitive race between multiple promises. When {@code count} amount of promises have been fulfilled,
     * the returned promise is fulfilled with a {@link List} that contains the fulfillment values of the winners in order of resolution.
     *
     * If too many promises are rejected so that the promise can never become fulfilled, it will be immediately rejected with an {@link TooManyErrorsException}
     * with a nested {@link CompositeException} of the rejection reasons in the order they were thrown in.
     *
     * @param count The required number of promises to be fulfilled
     * @param promises The {@link List} of promises
     * @return A promise that combines the values of the winners into a {@link List}
     */
    @SafeVarargs
    public static <T> Promise<List<T>> some(final int count, Promise<T>... promises) {
        return some(count, asList(promises));
    }

    /**
     * Initiate a competitive race between multiple promises. When {@code count} amount of promises have been fulfilled,
     * the returned promise is fulfilled with a {@link List} that contains the fulfillment values of the winners in order of resolution.
     *
     * If too many promises are rejected so that the promise can never become fulfilled, it will be immediately rejected with an {@link TooManyErrorsException}
     * (optionally with a nested {@link CompositeException}) of the rejection reason(s) in the order they were thrown in.
     *
     * @param count The required number of promises to be fulfilled
     * @param promises The {@link List} of promises
     * @return A promise that combines the values of the winners into a {@link List}
     */
    public static <T> Promise<List<T>> some(final int count, final Iterable<Promise<T>> promises) {
        if (count == 0) {
            return just(Collections.<T>emptyList());
        }

        final List<Throwable> errors = new ArrayList<Throwable>(Math.min(count, 16));
        final List<Observable<T>> observables = coerceToList(promises, Promise.<T>coerceToObservable());

        if (observables.size() < count) {
            throw new IllegalArgumentException("Iterable does not contains enough promises");
        }

        return from(merge(coerceToList(observables, new Func2<Observable<T>, Integer, Observable<T>>() {
            @Override
            public Observable<T> call(Observable<T> observable, Integer integer) {
                return observable.onErrorResumeNext(new Func1<Throwable, Observable<T>>() {
                    @Override
                    public Observable<T> call(Throwable throwable) {
                        synchronized (errors) {
                            errors.add(throwable);
                            if (observables.size() - errors.size() < count) {
                                throw new TooManyErrorsException(errors.size() == 1 ? errors.get(0) : new CompositeException(errors));
                            }
                        }

                        return Observable.empty();
                    }
                });
            }
        })).take(count).toList());
    }


    /**
     * Only return the values of promises that are successfully fulfilled,
     * the returned promise is fulfilled with a {@link List} that contains the fulfillment values in order of resolution.
     *
     * @param promises The {@link List} of promises
     * @return A promise that combines the values into a {@link List}
     */
    @SafeVarargs
    public static <T> Promise<List<T>> any(Promise<T>... promises) {
        return any(asList(promises));
    }

    /**
     * Only return the values of promises that are successfully fulfilled,
     * the returned promise is fulfilled with a {@link List} that contains the fulfillment values in order of resolution.
     *
     * @param promises The {@link List} of promises
     * @return A promise that combines the values into a {@link List}
     */
    public static <T> Promise<List<T>> any(final Iterable<Promise<T>> promises) {
        return from(merge(coerceToList(promises, Promise.<T>ignoreRejection())).toList());
    }

    /**
     * Modifies an Promise to perform its callbacks on a specified {@link Scheduler}
     */
    public Promise<T> callbacksOn(Scheduler scheduler) {
        return new Promise<T>(this.observable.observeOn(scheduler));
    }

    /**
     * Maps this promise to a promise of type U by applying the specified function.
     */
    public <U> Promise<U> map(Func1<T, U> func) {
        return new Promise<U>(this.observable.map(func));
    }

    /**
     * Returns a promise that transforms into another promise when the source promise is rejected.
     * @param func The function supplying the promise when the source promise is rejected.
     */
    public Promise<T> onErrorReturn(final Func1<Throwable, Promise<T>> func) {
        return new Promise<T>(this.observable.onErrorResumeNext(new Func1<Throwable, Observable<T>>() {
            @Override
            public Observable<T> call(Throwable throwable) {
                return func.call(throwable).observable;
            }
        }));
    }

    /**
     * Returns a promise that transforms into another promise when the source promise is rejected.
     * @param other The promise to transform into when the source promise is rejected.
     */
    public Promise<T> onErrorReturn(final Promise<T> other) {
        return new Promise<T>(this.observable.onErrorResumeNext(other.observable));
    }

    /**
     * Returns a promise that transforms into another promise when the source promise is rejected.
     * @param exceptionClass The type of exception to catch, if the {@link Throwable} is not an instance of this class the returned promise will still be rejected.
     * @param func The function supplying the promise when the source promise is rejected.
     */
    public <E extends Throwable> Promise<T> onErrorReturn(final Class<E> exceptionClass, final Func1<E, Promise<T>> func) {
        return new Promise<T>(this.observable.onErrorResumeNext(new Func1<Throwable, Observable<T>>() {
            @Override
            @SuppressWarnings("unchecked")
            public Observable<T> call(Throwable throwable) {
                if (exceptionClass.isAssignableFrom(throwable.getClass())) {
                    return func.call((E) throwable).observable;
                }

                return Observable.error(throwable);
            }
        }));
    }

    /**
     * Returns a promise that transforms into another promise when the source promise is rejected.
     * @param exceptionClass The type of exception to catch, if the {@link Throwable} is not an instance of this class the returned promise will still be rejected.
     * @param other The promise to transform into when the source promise is rejected.
     */
    public <E extends Throwable> Promise<T> onErrorReturn(final Class<E> exceptionClass, final Promise<T> other) {
        return onErrorReturn(exceptionClass, new Func1<E, Promise<T>>() {
            @Override
            public Promise<T> call(E throwable) {
                return other;
            }
        });
    }

    /**
     * Maps the result of this promise to a promise for a result of type U, and flattens that to be a single promise for U by applying the specified function.
     */
    public <U> Promise<U> flatMap(final Func1<T, Promise<U>> func) {
        return new Promise<U>(this.observable.flatMap(new Func1<T, Observable<U>>() {
            @Override
            public Observable<U> call(T value) {
                return func.call(value).observable;
            }
        }));
    }

    /**
     * Attach a callback for when the promise is fulfilled. This callback cannot be unsubscribed.
     */
    public Promise<T> onSuccess(Action1<T> success) {
        return new Promise<T>(this.observable.doOnNext(success));
    }

    /**
     * Attach a callback for when the promise is rejected. This callback cannot be unsubscribed.
     */
    public Promise<T> onError(Action1<Throwable> error) {
        return new Promise<T>(this.observable.doOnError(error));
    }

    /**
     * Attach a callback for when the promise is rejected. This callback cannot be unsubscribed.
     * @param throwableClass The exception class (or subclasses) you want to attach the error callback to
     */
    @SuppressWarnings("unchecked")
    public <E extends Throwable> Promise<T> onError(final Class<E> throwableClass, final Action1<E> error) {
        return new Promise<T>(this.observable.doOnError(new Action1<Throwable>() {
            @Override
            public void call(Throwable throwable) {
                if (throwableClass.isAssignableFrom(throwable.getClass())) {
                    error.call((E) throwable);
                }
            }
        }));
    }

    /**
     * Add a callback for when the promise is either fulfilled or rejected. This callback cannot be unsubscribed.
     */
    public Promise<T> onFinally(Action0 finallyDo) {
        return new Promise<T>(this.observable.doOnTerminate(finallyDo));
    }

    /**
     * Attach callbacks for when the promise is fulfilled.
     * @return Subscription so you can unsubscribe
     */
    public Subscription then(final Action1<T> fulfilmentCallback) {
        return this.observable.subscribe(fulfilmentCallback, new Action1<Throwable>() {
            @Override
            public void call(Throwable throwable) {
                // Ignore error
            }
        });
    }

    /**
     * Attach callbacks for when the promise is fulfilled or rejected.
     * @return Subscription so you can unsubscribe
     */
    public Subscription then(final Action1<T> fulfilmentCallback, final Action1<Throwable> rejectedCallback) {
        return this.observable.subscribe(fulfilmentCallback, rejectedCallback);
    }

    /**
     * Attach callbacks for when the promise is fulfilled, rejected and for when it's either fulfilled or rejected.
     * @return Subscription so you can unsubscribe
     */
    public Subscription then(final Action1<T> fulfilmentCallback, final Action1<Throwable> rejectedCallback, final Action0 onFinally) {
        return this.observable.subscribe(fulfilmentCallback, rejectedCallback, onFinally);
    }

    /**
     * Attach callbacks for when the promise gets fulfilled or rejected.
     * @return Subscription so you can unsubscribe
     */
    public Subscription then(final PromiseObserverBuilder<T> builder) {
        return then(builder.build());
    }

    /**
     * Attach callbacks for when the promise gets fulfilled or rejected.
     * @return Subscription so you can unsubscribe
     */
    public Subscription then(final PromiseObserver<T> observer) {
        return this.observable.subscribe(new Observer<T>() {
            @Override
            public void onCompleted() {
                observer.onFinally();
            }

            @Override
            public void onError(Throwable e) {
                try {
                    observer.onRejected(e);
                } finally {
                    observer.onFinally();
                }
            }

            @Override
            public void onNext(T t) {
                observer.onFulfilled(t);
            }
        });
    }

    /**
     * Returns a promise that mirrors the source promise but is fulfilled shifted forward in time by a
     * specified delay. Error notifications from the source promise are not delayed.
     */
    public Promise<T> delay(long delay, TimeUnit unit) {
        return new Promise<T>(observable.delay(delay, unit));
    }

    /**
     * Returns a promise that mirrors the source promise but applies a timeout policy.
     * If the promise isn't completed within the specified timeout duration,
     * the resulting promise is rejected with a {@code TimeoutException}.
     */
    public Promise<T> timeout(long timeout, TimeUnit timeUnit) {
        return new Promise<T>(observable.timeout(timeout, timeUnit));
    }

    /**
     * Returns a promise that mirrors the source promise but applies a timeout policy.
     * If the promise isn't completed within the specified timeout duration,
     * the resulting promise transforms into a fallback Promise.
     */
    public Promise<T> timeout(long timeout, TimeUnit timeUnit, Promise<T> fallback) {
        return new Promise<T>(observable.timeout(timeout, timeUnit, fallback.observable));
    }

    /**
     * Combines the values of the promises into a promise of type R by applying the specified function. If any of the promises are rejected the returned promise is immediately rejected.
     */
    public static <T1, T2, R> Promise<R> join(Promise<T1> p1, Promise<T2> p2, final Func2<T1, T2, R> joinFunction) {
        return new Promise<R>(Observable.zip(p1.observable, p2.observable, joinFunction));
    }

    /**
     * Combines the values of the promises into a promise of type R by applying the specified function. If any of the promises are rejected the returned promise is immediately rejected.
     */
    public static <T1, T2, T3, R> Promise<R> join(Promise<T1> p1, Promise<T2> p2, Promise<T3> p3, final Func3<T1, T2, T3, R> joinFunction) {
        return new Promise<R>(Observable.zip(p1.observable, p2.observable, p3.observable, joinFunction));
    }

    /**
     * Combines the values of the promises into a promise of type R by applying the specified function. If any of the promises are rejected the returned promise is immediately rejected.
     */
    public static <T1, T2, T3, T4, R> Promise<R> join(Promise<T1> p1, Promise<T2> p2, Promise<T3> p3, Promise<T4> p4, final Func4<T1, T2, T3, T4, R> joinFunction) {
        return new Promise<R>(Observable.zip(p1.observable, p2.observable, p3.observable, p4.observable, joinFunction));
    }

    /**
     * Combines the values of the promises into a promise of type R by applying the specified function. If any of the promises are rejected the returned promise is immediately rejected.
     */
    public static <T1, T2, T3, T4, T5, R> Promise<R> join(Promise<T1> p1, Promise<T2> p2, Promise<T3> p3, Promise<T4> p4, Promise<T5> p5, final Func5<T1, T2, T3, T4, T5, R> joinFunction) {
        return new Promise<R>(Observable.zip(p1.observable, p2.observable, p3.observable, p4.observable, p5.observable, joinFunction));
    }

    /**
     * Combines the values of the promises into a promise of type R by applying the specified function. If any of the promises are rejected the returned promise is immediately rejected.
     */
    public static <T1, T2, T3, T4, T5, T6, R> Promise<R> join(Promise<T1> p1, Promise<T2> p2, Promise<T3> p3, Promise<T4> p4, Promise<T5> p5, Promise<T6> p6, final Func6<T1, T2, T3, T4, T5, T6, R> joinFunction) {
        return new Promise<R>(Observable.zip(p1.observable, p2.observable, p3.observable, p4.observable, p5.observable, p6.observable, joinFunction));
    }

    /**
     * Combines the values of the promises into a promise of type R by applying the specified function. If any of the promises are rejected the returned promise is immediately rejected.
     */
    public static <T1, T2, T3, T4, T5, T6, T7, R> Promise<R> join(Promise<T1> p1, Promise<T2> p2, Promise<T3> p3, Promise<T4> p4, Promise<T5> p5, Promise<T6> p6, Promise<T7> p7, final Func7<T1, T2, T3, T4, T5, T6, T7, R> joinFunction) {
        return new Promise<R>(Observable.zip(p1.observable, p2.observable, p3.observable, p4.observable, p5.observable, p6.observable, p7.observable, joinFunction));
    }

    /**
     * Combines the values of the promises into a promise of type R by applying the specified function. If any of the promises are rejected the returned promise is immediately rejected.
     */
    public static <T1, T2, T3, T4, T5, T6, T7, T8, R> Promise<R> join(Promise<T1> p1, Promise<T2> p2, Promise<T3> p3, Promise<T4> p4, Promise<T5> p5, Promise<T6> p6, Promise<T7> p7, Promise<T8> p8, final Func8<T1, T2, T3, T4, T5, T6, T7, T8, R> joinFunction) {
        return new Promise<R>(Observable.zip(p1.observable, p2.observable, p3.observable, p4.observable, p5.observable, p6.observable, p7.observable, p8.observable, joinFunction));
    }

    /**
     * Combines the values of the promises into a promise of type R by applying the specified function. If any of the promises are rejected the returned promise is immediately rejected.
     */
    public static <T1, T2, T3, T4, T5, T6, T7, T8, T9, R> Promise<R> join(Promise<T1> p1, Promise<T2> p2, Promise<T3> p3, Promise<T4> p4, Promise<T5> p5, Promise<T6> p6, Promise<T7> p7, Promise<T8> p8, Promise<T9> p9, final Func9<T1, T2, T3, T4, T5, T6, T7, T8, T9, R> joinFunction) {
        return new Promise<R>(Observable.zip(p1.observable, p2.observable, p3.observable, p4.observable, p5.observable, p6.observable, p7.observable, p8.observable, p9.observable, joinFunction));
    }

    /**
     * Used in tests to block and wait for the result. This is _not_ meant
     * to be used in the normal code base, only for tests!
     */
    public T blocking() {
        return observable.toBlocking().single();
    }

    private static <T,R> List<R> coerceToList(Iterable<T> iterable, Func2<T, Integer, R> func) {
        ArrayList<R> result = iterable instanceof Collection ? new ArrayList<R>(((Collection) iterable).size()) : new ArrayList<R>();
        int index = 0;
        for (T o : iterable) {
            result.add(func.call(o, index++));
        }
        return result;
    }

    private static <T> Func2<Promise<T>, Integer, Observable<T>> ignoreRejection() {
        return new Func2<Promise<T>, Integer, Observable<T>>() {
            @Override
            public Observable<T> call(Promise<T> promise, Integer index) {
                return promise.observable.onErrorResumeNext(new Func1<Throwable, Observable<T>>() {
                    @Override
                    public Observable<T> call(Throwable throwable) {
                        return Observable.empty();
                    }
                });
            }
        };
    }

    private static <T> Observable<T> applyObserveOnScheduler(final Observable<T> observable, final Scheduler scheduler) {
        if (scheduler != null) {
            return observable.observeOn(scheduler);
        }

        return observable;
    }

    private static <T> Func2<Promise<T>, Integer, Observable<T>> coerceToObservable() {
        return new Func2<Promise<T>, Integer, Observable<T>>() {
            @Override
            public Observable<T> call(Promise<T> p, Integer index) {
                return p.observable;
            }
        };
    }

    /**
     * Gets the underlying observable
     */
    protected Observable<T> getObservable() {
        return observable;
    }
}

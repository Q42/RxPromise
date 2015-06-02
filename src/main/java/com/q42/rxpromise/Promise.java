package com.q42.rxpromise;

import rx.Observable;
import rx.Observer;
import rx.Scheduler;
import rx.Subscription;
import rx.exceptions.CompositeException;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.schedulers.Schedulers;
import rx.subjects.ReplaySubject;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static java.util.Arrays.asList;
import static rx.Observable.merge;

/**
 * A promise wrapper around RxJava's {@link Observable}. For simplified handling of Observables that emit a single value.
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

        applyObserveOnScheduler(observable.single(), DEFAULT_CALLBACKS_SCHEDULER).subscribe(subject);
        this.observable = subject;
    }

    /**
     * Creates a new promise based on the observable, this must be an observable that emits a single item.
     * If the observable emits more than one item or no items the promise is rejected with an
     * {@code IllegalArgumentException} or {@code NoSuchElementException} respectively.
     */
    public static <T> Promise<T> promise(Observable<T> observable) {
        return new Promise<>(observable);
    }

    /**
     * Returns a promise that executes the specified {@link Callable} asynchronously on the {@link #DEFAULT_ASYNC_SCHEDULER}
     */
    public static <T> Promise<T> async(final Callable<T> callable) {
        return promise(callable, DEFAULT_ASYNC_SCHEDULER);
    }

    /**
     * Returns a promise that executes the specified {@link Callable} on the specified {@link Scheduler}
     */
    public static <T> Promise<T> promise(final Callable<T> callable, final Scheduler scheduler) {
        return promise(Observable.<T>create(subscriber -> {
            try {
                subscriber.onNext(callable.call());
                subscriber.onCompleted();
            } catch (Throwable throwable) {
                subscriber.onError(throwable);
            }
        }).subscribeOn(scheduler));
    }

    /**
     * Returns a promise that fulfills with the supplied value immediately.
     */
    public static <T> Promise<T> just(final T value) {
        return promise(Observable.just(value));
    }

    /**
     * Returns a promise that will be rejected immediately with the supplied error
     */
    public static <T> Promise<T> error(final Throwable throwable) {
        return promise(Observable.error(throwable));
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
        final List<Observable<Tuple<T>>> listWithIndex = coerceToList(promises, (promise, index) -> promise.observable.map(t -> new Tuple<>(index, t)));

        return promise(merge(listWithIndex).toList().map(tuples -> {
            T[] resultWithoutIndexes = (T[]) new Object[tuples.size()];
            for (Tuple<T> tuple : tuples) {
                resultWithoutIndexes[tuple.index] = tuple.b;
            }
            return Arrays.asList(resultWithoutIndexes);
        }));
    }

    private static class Tuple<B> {
        private final Integer index;
        private final B b;

        private Tuple(Integer index, B b) {
            this.index = index;
            this.b = b;
        }
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
        return some(count, Arrays.asList(promises));
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
            return just(Collections.emptyList());
        }

        final List<Throwable> errors = new ArrayList<>(Math.min(count, 16));
        final List<Observable<T>> list = coerceToList(promises, (promise, integer) -> promise.observable);

        if (list.size() < count) {
            throw new IllegalArgumentException("Iterable does not contains enough promises");
        }

        return promise(merge(coerceToList(list, (observable, index) -> observable.onErrorResumeNext(throwable -> {
            synchronized (errors) {
                errors.add(throwable);
                if (list.size() - errors.size() < count) {
                    throw new TooManyErrorsException(errors.size() == 1 ? errors.get(0) : new CompositeException(errors));
                }
            }

            return Observable.empty();
        }))).take(count).toList());
    }

    private static <T,R> List<R> coerceToList(Iterable<T> iterable, Func2<T, Integer, R> func) {
        ArrayList<R> result = iterable instanceof Collection ? new ArrayList<>(((Collection) iterable).size()) : new ArrayList<>();
        int index = 0;
        for (T o : iterable) {
            result.add(func.call(o, index++));
        }
        return result;
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
        return any(Arrays.asList(promises));
    }

    /**
     * Only return the values of promises that are successfully fulfilled,
     * the returned promise is fulfilled with a {@link List} that contains the fulfillment values in order of resolution.
     *
     * @param promises The {@link List} of promises
     * @return A promise that combines the values into a {@link List}
     */
    public static <T> Promise<List<T>> any(final Iterable<Promise<T>> promises) {
        return promise(merge(coerceToList(promises, (promise, index) -> promise.observable.onErrorResumeNext(throwable -> Observable.empty()))).toList());
    }

    /**
     * Modifies an Promise to perform its callbacks on a specified {@link Scheduler},
     * asynchronously with an unbounded buffer.
     */
    public Promise<T> callbacksOn(Scheduler scheduler) {
        return new Promise<>(this.observable.observeOn(scheduler));
    }

    /**
     * Maps this promise to a promise of type U.
     */
    public <U> Promise<U> map(Func1<T, U> func) {
        return new Promise<>(this.observable.map(func));
    }

    /**
     * Returns a promise that transforms into another promise when the source promise is rejected.
     * @param func The function supplying the promise when the source promise is rejected.
     */
    public Promise<T> onErrorReturn(final Func1<Throwable, Promise<T>> func) {
        return new Promise<>(this.observable.onErrorResumeNext(throwable -> func.call(throwable).observable));
    }

    /**
     * Returns a promise that transforms into another promise when the source promise is rejected.
     * @param other The promise to transform into when the source promise is rejected.
     */
    public Promise<T> onErrorReturn(final Promise<T> other) {
        return new Promise<>(this.observable.onErrorResumeNext(other.observable));
    }

    /**
     * Maps the result of this promise to a promise for a result of type U, and flattens that to be a single promise for U.
     */
    public <U> Promise<U> flatMap(final Func1<T, Promise<U>> func) {
        return new Promise<>(this.observable.flatMap(value -> func.call(value).observable));
    }

    /**
     * Attach callbacks for when the promise gets fulfilled. If the promise is rejected, the error is ignored.
     * @return Subscription so you can unsubscribe
     */
    public Subscription then(final Action1<T> fulfilmentCallback) {
        return this.observable.subscribe(fulfilmentCallback, throwable -> {});
    }

    /**
     * Attach callbacks for when the promise gets fulfilled or rejected.
     * @return Subscription so you can unsubscribe
     */
    public Subscription then(final Action1<T> fulfilmentCallback, final Action1<Throwable> rejectedCallback) {
        return this.observable.subscribe(fulfilmentCallback, rejectedCallback);
    }

    /**
     * Attach callbacks for when the promise gets fulfilled or rejected.
     * @return Subscription so you can unsubscribe
     */
    public Subscription then(final Action1<T> fulfilmentCallback, final Action1<Throwable> rejectedCallback, final Action0 onFinally) {
        return this.observable.subscribe(fulfilmentCallback, rejectedCallback, onFinally);
    }

    /**
     * Attach callbacks for when the promise gets fulfilled or rejected.
     * @return Subscription so you can unsubscribe
     */
    public Subscription then(final Observer<T> observer) {
        return this.observable.subscribe(observer);
    }

    /**
     * Returns a promise that mirrors the source promise but is fulfilled shifted forward in time by a
     * specified delay. Error notifications from the source promise are not delayed.
     */
    public Promise<T> delay(long delay, TimeUnit unit) {
        return new Promise<>(observable.delay(delay, unit));
    }

    /**
     * Returns a promise that mirrors the source promise but applies a timeout policy.
     * If the promise isn't completed within the specified timeout duration,
     * the resulting promise is rejected with a {@code TimeoutException}.
     */
    public Promise<T> timeout(long timeout, TimeUnit timeUnit) {
        return new Promise<>(observable.timeout(timeout, timeUnit));
    }

    /**
     * Returns a promise that mirrors the source promise but applies a timeout policy.
     * If the promise isn't completed within the specified timeout duration,
     * the resulting promise transforms into a fallback Promise.
     */
    public Promise<T> timeout(long timeout, TimeUnit timeUnit, Promise<T> fallback) {
        return new Promise<>(observable.timeout(timeout, timeUnit, fallback.observable));
    }

    /**
     * Used in (integration) tests to block and wait for the result. This is _not_ meant
     * to be used in the normal code base, only for tests!
     */
    public T blocking() {
        return observable.toBlocking().single();
    }

    private static <T> Observable<T> applyObserveOnScheduler(final Observable<T> observable, final Scheduler scheduler) {
        if (scheduler != null) {
            return observable.observeOn(scheduler);
        }

        return observable;
    }

    protected Observable<T> getObservable() {
        return observable;
    }
}

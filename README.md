# RxPromise &nbsp; [![Build Status](https://travis-ci.org/Q42/RxPromise.png)](https://travis-ci.org/Q42/RxPromise) 

A Promise wrapper around RxJava's Observable. A promise represents a future value (usually of an asynchronous operation).

<a href="https://jitpack.io/com/github/Q42/RxPromise/1.3/javadoc/" target="_blank">JavaDoc</a>

## Why?
Why not just use [Observables](http://reactivex.io/RxJava/javadoc/rx/Observable.html) you ask? Well a Promise is easier to use when working with a single value (instead of a stream of values). Additionally, it has a consitent behaviour in terms of caching already fullfilled values.

### Semantics
This promise is eager and is always cached:
- Eager: The promise will start fulfilling it's value as soon as it's created.
- Cached: The value will be redeemed from it's origin only once. All calls will wait until the promise's value is successfully fulfilled or the promise is rejected with an exception. All subsequent calls will immediately get the already fulfilled value (or exception). 

For example, if the promise is a future value from a HTTP API call, the API call will always be called once, regardless of the complexity of the promise chain.

## Compatibility
This library is compatible with Java 6

## Installation
See [https://jitpack.io/#Q42/RxPromise](https://jitpack.io/#Q42/RxPromise) for instructions to include RxPromise as a gradle or maven dependency.

## Usage

```java
Promise.async(Test::longRunningOperation)
        .map(integer -> integer * 10)
        .flatMap(Test::anotherLongRunningOperation)
        .onError(SomeException.class, someException -> log(someException))
        .onSuccess(o -> doSomeThingWith(o));
````

### Subscriptions
You can unsubscribe callbacks and subscribe again later on, resubscribing to the promise will not execute the `longRunningOperation` again, instead it will use the already fullfilled value or wait until the promise is fullfilled.

```java
Promise<String> promise = Promise.async(Test::longRunningOperation);
Subscription subscription = promise.then(System.out::println);

// Unsubscribe, the callback 'System.out::println' will never be invoked
subscription.unsubscribe();

// Resubscribe
promise.then(System.err::println);
```

### Multiple promises

Wait for all promises to be fullfilled.
```java
Promise<String> a = ...;
Promise<String> b = ...;
Promise<String> c = ...;
Promise<String> d = ...;

Promise.all(a, b, c, d).onSuccess(System.out::println); // prints:  [a, b, c, d]
```

Wait for any promises to be fullfilled, promises that are rejected are ignored.
```java
Promise<String> a = ...;
Promise<String> b = ...; // error
Promise<String> c = ...;
Promise<String> d = ...; // error

Promise.any(a, b, c, d).onSuccess(System.out::println); // prints:  [a, c]
```

Wait for a specific amount of promises to be fullfilled, promises that are rejected are ignored. If there are not enough fullfilled promises to be returned, the `somePromise` is rejected.
```java
Promise<String> a = ...;
Promise<String> b = ...; // error
Promise<String> c = ...;
Promise<String> d = ...;

List<String> somePromise = Promise.some(2, a, b, c, d);
somePromise.onSuccess(System.out::println) // prints:  [a, c]
```

Combine values of promises with different types
```java
Promise<String> p1 = ...;
Promise<Integer> p2 = ...;
Promise<Boolean> p3 = ...;
Promise.join(p1, p2, p3, (s, i, b) -> System.out.printf("%s, %d, %s%n", s, i, b));
// prints: a, 1, true
```

## Threads
You can (globally) specify the thread callback should be scheduled on.
```java
// Set callbacks scheduler for all promises
Promise.DEFAULT_CALLBACKS_SCHEDULER = AndroidSchedulers.mainThread();

Promise.async(Test::longRunningOperation).then(o -> {
    // This is always called on the Android main Thread
    // Update the view here
});
```
or
```java
Promise.async(Test::longRunningOperation).callbacksOn(AndroidSchedulers.mainThread()).then(o -> {
    // This is called on the Android main Thread
    // Update the view here
});
```

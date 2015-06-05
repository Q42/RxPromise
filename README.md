# RxPromise
A Promise wrapper around RxJava's Observable. A promise represents the eventual result of an asynchronous operation.

[JavaDoc](https://jitpack.io/com/github/Q42/RxPromise/1.0/javadoc/)

## Why?
Why not just use [Observables](http://reactivex.io/RxJava/javadoc/rx/Observable.html) you ask? Well a Promise is easier to use when working with a single value (instead of a stream of values). Additionally, it has a consitent behaviour in terms of caching already fullfilled values.

## Compatibility
This library is compatible with Java 7

## Installation
See [https://jitpack.io/#Q42/RxPromise](https://jitpack.io/#Q42/RxPromise) for instructions to include RxPromise as a gradle or maven dependency.

## Usage

```java
Promise.async(Test::longRunningOperation)
        .map(integer -> integer * 10)
        .flatMap(Test::anotherLongRunningOperation)
        .then(o -> doSomeThingWith(o));
````

### Subscriptions
You can unsubscribe callbacks and subscribe again later on, resubscribing to the promise will not execute the `longRunningOperation` again, instead it will use the already fullfilled value or wait until the promise is fullfilled.

```java
Promise<String> promise = Promise.async(Test::longRunningOperation);
Subscription subscription = promise.then(System.out::println);

// Unsubscribe, the callback will never be invoked
subscription.unsubscribe();

// Subscribe again
promise.then(System.out::println);
```

### Multiple promises

Wait for all promises to be fullfilled.
```java
Promise<String> a = ...;
Promise<String> b = ...;
Promise<String> c = ...;
Promise<String> d = ...;

Promise.all(a, b, c, d).then(System.out::println); // [a, b, c, d]
```

Wait for any promises to be fullfilled, promises that are rejected are ignored.
```java
Promise<String> a = ...;
Promise<String> b = ...; // error
Promise<String> c = ...;
Promise<String> d = ...; // error

Promise.any(a, b, c, d).then(System.out::println); // [a, c]
```

Wait for a specific amount of promises to be fullfilled, promises that are rejected are ignored. If there are not enough fullfilled promises to be returned, the `somePromise` is rejected.
```java
Promise<String> a = ...;
Promise<String> b = ...; // error
Promise<String> c = ...;
Promise<String> d = ...;

List<String> somePromise = Promise.some(2, a, b, c, d);
somePromise.then(System.out::println) // [a, c]
```
## Cancellation
Promises can be cancelled, you can either cancel a single promise or cancel _all_ promises in a chain all the way up to it's origin. When a promise was already successfully fulfilled cancellation has no effect.
```java
Promise<String> a = Promise.async(() -> longOp());
Promise<String> b = a.flatMap(s -> otherLongOp());

b.cancel(); // Only cancels b and otherLongOp
b.cancelAll(); // Cancels the entire chain: a, b and otherLongOp and longOp
a.cancelAll(); // The same as b.cancelAll(); cancels the entire chain: a, b and otherLongOp and longOp
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

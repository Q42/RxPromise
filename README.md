# RxPromise
A Promise wrapper around RxJava's Observable. A promise represents the eventual result of an asynchronous operation.

## Why?
Why not just use [Observables](http://reactivex.io/RxJava/javadoc/rx/Observable.html) you ask? Well a Promise is easier to use when working with single values instead of a stream of values. Additionally is has a consitent behaviour in terms of caching already fullfilled values.

## Compatibility
This library is compatible with Java 7

## Installation
See [https://jitpack.io/#Q42/RxPromise](https://jitpack.io/#Q42/RxPromise) for instruction to include RxPromise as a gradle or maven dependency.

## Usage
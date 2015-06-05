package com.q42.rxpromise;

/**
 * Exception used when too many promises are rejected in {@link Promise#some}
 */
public class TooManyErrorsException extends RuntimeException {
    public TooManyErrorsException(Throwable cause) {
        super(cause);
    }
}
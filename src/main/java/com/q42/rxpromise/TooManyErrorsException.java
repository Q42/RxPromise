package com.q42.rxpromise;

public class TooManyErrorsException extends RuntimeException {
    public TooManyErrorsException(Throwable cause) {
        super(cause);
    }
}
package com.q42.rxpromise;

import org.hamcrest.Matchers;
import org.junit.Test;
import rx.exceptions.CompositeException;

import java.util.List;
import java.util.concurrent.Callable;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

/**
 * Created by thijs on 02-06-15.
 */
public class PromiseTest {
    @Test
    public void testAll() {
        List<String> result = Promise.all(succes("a", 500), succes("b", 400), succes("c", 200), succes("d", 300), succes("e", 0)).blocking();
        assertThat(result, contains("a", "b", "c", "d", "e"));

        assertThat(Promise.all().blocking(), iterableWithSize(0));

        try {
            Promise.all(error(new TestException(), 500), succes("b", 400), succes("c", 200), succes("d", 300), succes("e", 0)).blocking();
            fail();
        } catch (RuntimeException e) {
            assertThat(e.getCause(), is(instanceOf(TestException.class)));
        }

        try {
            Promise.all(error("a", 500), error("b", 400), error("c", 200), error("d", 300), error("e", 0)).blocking();
            fail();
        } catch (RuntimeException e) {
            assertThat(e.getCause(), is(instanceOf(Exception.class)));
            assertThat(e.getCause().getMessage(), is("e"));
        }
    }

    @Test
    public void testSome() {
        assertThat(Promise.some(1, succes("a", 500), succes("b", 400), succes("c", 200), succes("d", 300), succes("e", 0)).blocking(),
                contains("e"));

        assertThat(Promise.some(2, succes("a", 500), succes("b", 400), succes("c", 200), succes("d", 300), succes("e", 0)).blocking(),
                contains("e", "c"));

        assertThat(Promise.some(3, succes("a", 500), succes("b", 400), succes("c", 200), succes("d", 300), succes("e", 0)).blocking(),
                contains("e", "c", "d"));

        assertThat(Promise.some(4, succes("a", 500), succes("b", 400), succes("c", 200), succes("d", 300), succes("e", 0)).blocking(),
                contains("e", "c", "d", "b"));

        assertThat(Promise.some(5, succes("a", 500), succes("b", 400), succes("c", 200), succes("d", 300), succes("e", 0)).blocking(),
                contains("e", "c", "d", "b", "a"));
    }

    @Test
    public void testSomeWithErrors() {
        assertThat(Promise.some(1, succes("a", 500), succes("b", 400), succes("c", 200), succes("d", 300), error("e", 0)).blocking(),
                contains("c"));

        assertThat(Promise.some(2, succes("a", 500), succes("b", 400), succes("c", 200), error("d", 300), error("e", 0)).blocking(),
                contains("c", "b"));

        assertThat(Promise.some(3, succes("a", 500), succes("b", 400), succes("c", 200), error("d", 300), error("e", 0)).blocking(),
                contains("c", "b", "a"));

        assertThat(Promise.some(1, error("a", 500), error("b", 400), succes("c", 200), error("d", 300), error("e", 0)).blocking(),
                contains("c"));

        assertThat(Promise.some(0, error("a", 500), error("b", 400), succes("c", 200), error("d", 300), error("e", 0)).blocking(),
                Matchers.<String>iterableWithSize(0));

        try {
            Promise.some(3, error(new TestException(), 50), error("b", 100), error("c", 150)).blocking();
            fail();
        } catch (Exception e) {
            assertThat(e.getCause(), is(instanceOf(TestException.class)));
        }

        try {
            Promise.some(3, error(new TestException(), 100), error("b", 50), error("c", 150)).blocking();
            fail();
        } catch (Exception e) {
            assertThat(e.getCause().getMessage(), is("b"));
        }

        testCompositeException(2, Promise.some(2, error("a", 50), error("b", 100), succes("c", 150)));
        testCompositeException(3, Promise.some(1, error("a", 50), error("b", 100), error("c", 150)));

        try {
            Promise.some(4, succes("a", 100), succes("b", 50), succes("c", 150)).blocking();
            fail();
        } catch (IllegalArgumentException e) {}
    }

    @Test
    public void testAny() {
        assertThat(Promise.any(succes("a", 500), succes("b", 400), succes("c", 200), succes("d", 300), succes("e", 0)).blocking(),
                contains("e", "c", "d", "b", "a"));

        assertThat(Promise.any(succes("a", 500), succes("b", 400), succes("c", 200), succes("d", 300), error("e", 0)).blocking(),
                contains("c", "d", "b", "a"));

        assertThat(Promise.any(succes("a", 500), succes("b", 400), succes("c", 200), error("d", 300), error("e", 0)).blocking(),
                contains("c", "b", "a"));

        assertThat(Promise.any(succes("a", 500), succes("b", 400), error("c", 200), error("d", 300), error("e", 0)).blocking(),
                contains("b", "a"));

        assertThat(Promise.any(succes("a", 500), error("b", 400), error("c", 200), error("d", 300), error("e", 0)).blocking(),
                contains("a"));

        assertThat(Promise.any(error("a", 500), error("b", 400), error("c", 200), error("d", 300), error("e", 0)).blocking(),
                Matchers.<String>iterableWithSize(0));
    }

    private void testCompositeException(int count, Promise<?> promise) {
        try {
            promise.blocking();
            fail();
        } catch (TooManyErrorsException e) {
            assertThat(e.getCause(), is(instanceOf(CompositeException.class)));
            assertThat(((CompositeException) e.getCause()).getExceptions(), Matchers.<Throwable>iterableWithSize(count));
        }
    }

    private <T> Promise<T> succes(final T value, final long sleep) {
        return Promise.async(new Callable<T>() {
            @Override
            public T call() throws Exception {
                Thread.sleep(sleep);
                return value;
            }
        });
    }

    private Promise<String> error(String errorMessage, long sleep) {
        return error(new Exception(errorMessage), sleep);
    }

    private Promise<String> error(final Exception t, final long sleep) {
        return Promise.async(new Callable<String>() {
            @Override
            public String call() throws Exception {
                Thread.sleep(sleep);
                throw t;
            }
        });
    }

    public class TestException extends Exception {}
}
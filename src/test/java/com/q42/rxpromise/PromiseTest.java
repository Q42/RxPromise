package com.q42.rxpromise;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;
import rx.exceptions.CompositeException;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.schedulers.Schedulers;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

/**
 * Created by thijs on 02-06-15.
 */
public class PromiseTest {
    private final HashMap<String, Long> lastStartTimes = new HashMap<>();
    private final List<String> computed = new ArrayList<>();

    @Before
    public void before() {
        Promise.DEFAULT_CALLBACKS_SCHEDULER = null;
        lastStartTimes.clear();
        computed.clear();
    }

    @Test
    public void testAllConcurrent() {
        Promise.all(succes("a", 500), succes("b", 400), succes("c", 200), succes("d", 300), succes("e", 0)).blocking();
        testAllValuesConcurrent();
    }

    @Test
    public void testSomeConcurrent() {
        Promise.some(4, succes("a", 500), succes("b", 400), succes("c", 200), succes("d", 300), succes("e", 0)).blocking();
        testAllValuesConcurrent();
    }

    @Test
    public void testAnyConcurrent() {
        Promise.any(succes("a", 500), succes("b", 400), succes("c", 200), succes("d", 300), succes("e", 0)).blocking();
        testAllValuesConcurrent();
    }

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
    public void testCallbacksOn() throws InterruptedException {
        final ExecutorService executorService = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "singleTestThread");
            }
        });

        Promise.DEFAULT_CALLBACKS_SCHEDULER = Schedulers.from(executorService);
        final Map<String, Thread> callbackThread = new HashMap<>();
        succes("a", 300).then(new Action1<String>() {
            @Override
            public void call(String s) {
                l(s);
                callbackThread.put(s, Thread.currentThread());
            }
        }, printStackTraceAction());

        Thread.sleep(400);

        assertThat(callbackThread.get("a").getName(), is("singleTestThread"));

        executorService.shutdown();
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

    @Test
    public void testBuilder() {
        final AtomicBoolean success1 = new AtomicBoolean(false);
        final AtomicBoolean success2 = new AtomicBoolean(false);
        final AtomicBoolean finally1 = new AtomicBoolean(false);
        final AtomicBoolean finally2 = new AtomicBoolean(false);
        final AtomicBoolean error = new AtomicBoolean(false);

        Promise<String> a = succes("a", 200);
        a.then(new PromiseObserverBuilder<String>()
                .success(new Action1<String>() {
                    @Override
                    public void call(String s) {
                        success1.set(true);
                    }
                }).success(new Action1<String>() {
                    @Override
                    public void call(String s) {
                        success2.set(true);
                    }
                }).finallyDo(new Action0() {
                    @Override
                    public void call() {
                        finally1.set(true);
                    }
                }).finallyDo(new Action0() {
                    @Override
                    public void call() {
                        finally2.set(true);
                    }
                }).error(new Action1<Throwable>() {
                    @Override
                    public void call(Throwable throwable) {
                        error.set(true);
                    }
                }));

        a.blocking();

        assertThat(success1.get(), is(true));
        assertThat(success2.get(), is(true));
        assertThat(finally1.get(), is(true));
        assertThat(finally2.get(), is(true));
        assertThat(error.get(), is(false));
    }

    @Test
    public void testOnErrorReturn() {
        String a = error(new IllegalArgumentException(), 0).onErrorReturn(Promise.just("a")).blocking();
        assertThat(a, is("a"));

        String b = error(new IllegalArgumentException(), 100).onErrorReturn(new Func1<Throwable, Promise<String>>() {
            @Override
            public Promise<String> call(Throwable throwable) {
                return succes("b", 100);
            }
        }).blocking();

        assertThat(b, is("b"));


        String c = error(new TestException(), 0).onErrorReturn(TestException.class, Promise.just("c")).blocking();
        assertThat(c, is("c"));

        String d = error(new TestRuntimeException(), 100).onErrorReturn(TestRuntimeException.class, new Func1<TestRuntimeException, Promise<String>>() {
            @Override
            public Promise<String> call(TestRuntimeException e) {
                return succes("d", 100);
            }
        }).blocking();
        assertThat(d, is("d"));


        try {
            error(new TestRuntimeException(), 0).onErrorReturn(IndexOutOfBoundsException.class, Promise.just("d")).blocking();
            fail("Expected " + TestRuntimeException.class);
        } catch (Throwable e) {
            assertThat(e, is(instanceOf(TestRuntimeException.class)));
        }

        try {
            error(new TestException(), 0).onErrorReturn(IndexOutOfBoundsException.class, new Func1<IndexOutOfBoundsException, Promise<String>>() {
                @Override
                public Promise<String> call(IndexOutOfBoundsException throwable) {
                    return succes("b", 100);
                }
            }).blocking();
            fail("Expected " + TestException.class);
        } catch (Throwable e) {
            assertThat(e.getCause(), is(instanceOf(TestException.class)));
        }
    }

    @Test
    public void testBuilderWithErrors() {
        final AtomicBoolean success1 = new AtomicBoolean(false);
        final AtomicBoolean finally1 = new AtomicBoolean(false);
        final AtomicBoolean error1 = new AtomicBoolean(false);
        final AtomicBoolean error2 = new AtomicBoolean(false);
        final AtomicBoolean error3 = new AtomicBoolean(false);
        final AtomicBoolean error4 = new AtomicBoolean(false);

        Promise<String> a = error(new IllegalArgumentException(), 200);
        a.then(new PromiseObserverBuilder<String>()
                .success(new Action1<String>() {
                    @Override
                    public void call(String s) {
                        success1.set(true);
                    }
                }).finallyDo(new Action0() {
                    @Override
                    public void call() {
                        finally1.set(true);
                    }
                }).error(new Action1<Throwable>() {
                    @Override
                    public void call(Throwable throwable) {
                        error1.set(true);
                    }
                }).error(new Action1<Throwable>() {
                    @Override
                    public void call(Throwable throwable) {
                        error2.set(true);
                    }
                }).error(IllegalArgumentException.class, new Action1<IllegalArgumentException>() {
                    @Override
                    public void call(IllegalArgumentException throwable) {
                        error3.set(true);
                    }
                }).error(IndexOutOfBoundsException.class, new Action1<IndexOutOfBoundsException>() {
                    @Override
                    public void call(IndexOutOfBoundsException throwable) {
                        error4.set(true);
                    }
                }));

        try {
            a.blocking();
        } catch (IllegalArgumentException e) {}

        assertThat(success1.get(), is(false));
        assertThat(finally1.get(), is(true));
        assertThat(error1.get(), is(true));
        assertThat(error2.get(), is(true));
        assertThat(error3.get(), is(true));
        assertThat(error4.get(), is(false));
    }

    @Test
    public void testComputedOnce() throws InterruptedException {
        Promise<String> a = succes("a", 400);
        a.then(logAction(), printStackTraceAction());
        a.then(logAction(), printStackTraceAction());
        Thread.sleep(100);
        a.then(logAction(), printStackTraceAction());
        a.then(logAction(), printStackTraceAction());
        Thread.sleep(400);
        a.then(logAction(), printStackTraceAction());
        a.then(logAction(), printStackTraceAction());
        assertThat(computed, contains("a"));
    }

    @Test
    public void testPromiseIsEager() throws InterruptedException {
        Promise<String> a = succes("a", 50);
        Thread.sleep(100);
        assertThat(computed, contains("a"));
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

    private Promise<String> succes(final String value, final long sleep) {
        return Promise.async(new Callable<String>() {
            @Override
            public String call() throws Exception {
                l("Crunching " + value + "...");
                lastStartTimes.put(value, System.currentTimeMillis());
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    l("Error in " + value + " " + e.toString());
                    throw e;
                }
                computed.add(value);
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
                l("Crunching " + t.getClass().getSimpleName() + "...");
                Thread.sleep(sleep);
                throw t;
            }
        });
    }

    private void testAllValuesConcurrent() {
        testConcurrent(lastStartTimes.keySet());
    }

    private void testConcurrent(Iterable<String> values) {
        long now = System.currentTimeMillis();
        for (String value : values) {
            assertThat(lastStartTimes.get(value), Matchers.lessThanOrEqualTo(now + 50));
        }
    }

    public static void l(Object s) {
        System.out.printf("%s - %s - %s%n", new Date().toString(), Thread.currentThread().toString(), String.valueOf(s));
    }

    public class TestException extends Exception {}
    public class TestRuntimeException extends RuntimeException {}

    private Action1<Throwable> printStackTraceAction() {
        return new Action1<Throwable>() {
            @Override
            public void call(Throwable throwable) {
                throwable.printStackTrace();
            }
        };
    }

    private Action1<String> logAction() {
        return new Action1<String>() {
            @Override
            public void call(String s) {
                l(s);
            }
        };
    }
}
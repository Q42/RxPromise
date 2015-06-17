package com.q42.rxpromise;

import org.hamcrest.Matchers;
import org.junit.Before;
import org.junit.Test;
import rx.exceptions.CompositeException;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.functions.Func4;
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
    private final HashMap<String, Long> lastStartTimes = new HashMap<String, Long>();
    private final List<String> computed = new ArrayList<String>();

    @Before
    public void before() {
        Promise.DEFAULT_CALLBACKS_SCHEDULER = null;
        lastStartTimes.clear();
        computed.clear();
    }

    @Test
    public void testAllConcurrent() {
        Promise.all(succes("a", 500), succes("b", 400), succes("c", 200), succes("d", 300), succes("e", 0)).blocking();
        assertAllValuesConcurrent();
    }

    @Test
    public void testSomeConcurrent() {
        Promise.some(4, succes("a", 500), succes("b", 400), succes("c", 200), succes("d", 300), succes("e", 0)).blocking();
        assertAllValuesConcurrent();
    }

    @Test
    public void testAnyConcurrent() {
        Promise.any(succes("a", 500), succes("b", 400), succes("c", 200), succes("d", 300), succes("e", 0)).blocking();
        assertAllValuesConcurrent();
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
        final Map<String, Thread> callbackThread = new HashMap<String, Thread>();
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
                emptyIterable());

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
                emptyIterable());
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
                .onSuccess(new Action1<String>() {
                    @Override
                    public void call(String s) {
                        success1.set(true);
                    }
                }).onSuccess(new Action1<String>() {
                    @Override
                    public void call(String s) {
                        success2.set(true);
                    }
                }).onFinally(new Action0() {
                    @Override
                    public void call() {
                        finally1.set(true);
                    }
                }).onFinally(new Action0() {
                    @Override
                    public void call() {
                        finally2.set(true);
                    }
                }).onError(new Action1<Throwable>() {
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
    public void testOnSuccessAfterFulfillment() throws InterruptedException {
        final Promise<String> a = succes("a", 400);
        Thread.sleep(500);

        final List<String> calls = new ArrayList<String>(1);

        a.onSuccess(new Action1<String>() {
            @Override
            public void call(String s) {
                calls.add(s);
            }
        });

        Thread.sleep(100);

        assertThat(calls, contains("a"));
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
                .onSuccess(new Action1<String>() {
                    @Override
                    public void call(String s) {
                        success1.set(true);
                    }
                }).onFinally(new Action0() {
                    @Override
                    public void call() {
                        finally1.set(true);
                    }
                }).onError(new Action1<Throwable>() {
                    @Override
                    public void call(Throwable throwable) {
                        error1.set(true);
                    }
                }).onError(new Action1<Throwable>() {
                    @Override
                    public void call(Throwable throwable) {
                        error2.set(true);
                    }
                }).onError(IllegalArgumentException.class, new Action1<IllegalArgumentException>() {
                    @Override
                    public void call(IllegalArgumentException throwable) {
                        error3.set(true);
                    }
                }).onError(IndexOutOfBoundsException.class, new Action1<IndexOutOfBoundsException>() {
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

    @Test
    public void testJoin() throws InterruptedException {
        final Promise<String> p = Promise.join(succes("a", 400), succes("b", 500), Promise.just(1), Promise.just(true), new Func4<String, String, Integer, Boolean, String>() {
            @Override
            public String call(String a, String b, Integer integer, Boolean aBoolean) {
                return a + b + integer + aBoolean;
            }
        });

        assertAllValuesConcurrent();

        assertThat(p.blocking(), is("ab1true"));
    }

    @Test(expected = TestRuntimeException.class)
    public void testJoinError() throws InterruptedException {
        final Promise<String> p = Promise.join(succes("a", 400), error(new TestRuntimeException(), 500), Promise.just(1), Promise.just(true), new Func4<String, String, Integer, Boolean, String>() {
            @Override
            public String call(String a, String b, Integer integer, Boolean aBoolean) {
                return a + b + integer + aBoolean;
            }
        });

        p.blocking();
    }

    @Test
    public void testJoinPromise() throws InterruptedException {
        final Promise<Promise<String>> p = Promise.join(succes("a", 400), succes("b", 500), Promise.just(1), Promise.just(true), new Func4<String, String, Integer, Boolean, Promise<String>>() {
            @Override
            public Promise<String> call(String a, String b, Integer integer, Boolean aBoolean) {
                return Promise.just(a + b + integer + aBoolean);
            }
        });

        final Promise<String> f = p.flatMap(new Func1<Promise<String>, Promise<String>>() {
            @Override
            public Promise<String> call(Promise<String> stringPromise) {
                return stringPromise;
            }
        });

        assertThat(f.blocking(), is("ab1true"));
    }

    @Test
    public void tesOnSuccess() {
        final List<String> invoked = new ArrayList<String>(1);
        succes("a", 50).onSuccess(new Action1<String>() {
            @Override
            public void call(String s) {
                invoked.add(s);
            }
        }).blocking();
        assertThat(invoked, contains("a"));
    }

    @Test
    public void tesOnFinally() {
        final List<String> invoked = new ArrayList<String>(1);
        succes("a", 50).onFinally(new Action0() {
            @Override
            public void call() {
                invoked.add("a");
            }
        }).blocking();
        assertThat(invoked, contains("a"));

        try {
            invoked.clear();
            error(new TestRuntimeException(), 50).onFinally(new Action0() {
                @Override
                public void call() {
                    invoked.add("a");
                }
            }).blocking();
        } catch (TestRuntimeException e) {}
        assertThat(invoked, contains("a"));
    }

    @Test
    public void tesOnError() {
        final List<String> invoked = new ArrayList<String>(1);
        error(new TestException(), 50).onError(new Action1<Throwable>() {
            @Override
            public void call(Throwable throwable) {
                invoked.add("TestException");
            }
        }).onErrorReturn(Promise.just("z")).blocking();
        assertThat(invoked, contains("TestException"));

        invoked.clear();
        error(new TestException(), 50).onError(TestException.class, new Action1<TestException>() {
            @Override
            public void call(TestException throwable) {
                invoked.add("TestException");
            }
        }).onErrorReturn(Promise.just("z")).blocking();
        assertThat(invoked, contains("TestException"));

        invoked.clear();
        error(new TestRuntimeException(), 50).onError(TestRuntimeException.class, new Action1<TestRuntimeException>() {
            @Override
            public void call(TestRuntimeException throwable) {
                invoked.add("TestRuntimeException");
            }
        }).onErrorReturn(Promise.just("z")).blocking();
        assertThat(invoked, contains("TestRuntimeException"));

        invoked.clear();
        error(new TestException(), 50).onError(TestRuntimeException.class, new Action1<TestRuntimeException>() {
            @Override
            public void call(TestRuntimeException throwable) {
                invoked.add("TestException");
            }
        }).onErrorReturn(Promise.just("z")).blocking();
        assertThat(invoked, emptyIterable());

        invoked.clear();
        error(new TestRuntimeException(), 50).onError(TestException.class, new Action1<TestException>() {
            @Override
            public void call(TestException throwable) {
                invoked.add("TestException");
            }
        }).onErrorReturn(Promise.just("z")).blocking();
        assertThat(invoked, emptyIterable());
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

    private void assertAllValuesConcurrent() {
        assertConcurrent(lastStartTimes.keySet());
    }

    private void assertConcurrent(Iterable<String> values) {
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
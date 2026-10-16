package app.localcache;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

/** Controlled blocked backends exercise the same owner used by the foreground service. */
public final class SerialLifecycleChecks {
    public static void main(String[] args) throws Exception { runAll(); }

    public static void runAll() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch queued = new CountDownLatch(1);
        List<String> events = new ArrayList<>();
        try {
            worker.submit(() -> await(queued));
            SerialLifecycle lifecycle = new SerialLifecycle(worker, backend(events));
            long old = lifecycle.start();
            equal(old, lifecycle.start());
            lifecycle.stop(old);
            long current = lifecycle.start();
            lifecycle.stop(old); // Late destruction of the first service.
            equal(SerialLifecycle.State.STARTING, lifecycle.snapshot().state);
            queued.countDown();
            drain(worker);
            equal(Arrays.asList("stop", "start"), events);
            equal(SerialLifecycle.State.RUNNING, lifecycle.snapshot().state);
            lifecycle.stop(current);
            drain(worker);
            equal(SerialLifecycle.State.STOPPED, lifecycle.snapshot().state);
        } finally { queued.countDown(); worker.shutdownNow(); }

        worker = Executors.newSingleThreadExecutor();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        CountDownLatch closing = new CountDownLatch(1), closed = new CountDownLatch(1);
        AtomicInteger starts = new AtomicInteger();
        AtomicBoolean accepting = new AtomicBoolean();
        try {
            SerialLifecycle lifecycle = new SerialLifecycle(worker, new SerialLifecycle.Backend() {
                public void start() { starts.incrementAndGet(); }
                public void stop() { closing.countDown(); await(closed); }
                public void setAccepting(boolean value) { accepting.set(value); }
            });
            long old = lifecycle.start();
            drain(worker);
            lifecycle.stop(old);
            check(!accepting.get(), "stop did not immediately fence admission");
            check(closing.await(5, TimeUnit.SECONDS), "stop never entered");
            long current = caller.submit(lifecycle::start).get(1, TimeUnit.SECONDS);
            lifecycle.stop(old);
            equal(1, starts.get()); // No replacement writer before native/disk close.
            equal(SerialLifecycle.State.STARTING, lifecycle.snapshot().state);
            closed.countDown();
            drain(worker);
            equal(2, starts.get());
            equal(current, lifecycle.snapshot().generation);
            equal(SerialLifecycle.State.RUNNING, lifecycle.snapshot().state);
            check(accepting.get(), "replacement did not reopen admission");
            lifecycle.stop(old);
            check(accepting.get(), "stale service fenced the replacement");
        } finally { closed.countDown(); worker.shutdownNow(); caller.shutdownNow(); }

        worker = Executors.newSingleThreadExecutor();
        List<String> retryEvents = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        try {
            SerialLifecycle lifecycle = new SerialLifecycle(worker, new SerialLifecycle.Backend() {
                public void start() throws IOException {
                    retryEvents.add("start");
                    if (attempts.getAndIncrement() == 0) throw new IOException("bind failed after partial setup");
                }
                public void stop() { retryEvents.add("stop"); }
            });
            lifecycle.start();
            drain(worker);
            equal(SerialLifecycle.State.FAILED, lifecycle.snapshot().state);
            check(lifecycle.snapshot().error.getMessage().contains("bind failed"), "missing startup cause");
            lifecycle.start();
            drain(worker);
            equal(Arrays.asList("start", "stop", "start"), retryEvents);
            equal(SerialLifecycle.State.RUNNING, lifecycle.snapshot().state);
        } finally { worker.shutdownNow(); }

        worker = Executors.newSingleThreadExecutor();
        List<String> failureEvents = new ArrayList<>();
        AtomicInteger stops = new AtomicInteger();
        try {
            SerialLifecycle lifecycle = new SerialLifecycle(worker, new SerialLifecycle.Backend() {
                public void start() { failureEvents.add("start"); }
                public void stop() throws IOException {
                    failureEvents.add("stop");
                    if (stops.getAndIncrement() == 0) throw new IOException("storage still open");
                }
            });
            long owner = lifecycle.start();
            drain(worker);
            lifecycle.stop(owner);
            drain(worker);
            equal(SerialLifecycle.State.FAILED, lifecycle.snapshot().state);
            lifecycle.start();
            drain(worker);
            equal(Arrays.asList("start", "stop", "stop", "start"), failureEvents);
        } finally { worker.shutdownNow(); }

        worker = Executors.newSingleThreadExecutor();
        CountDownLatch starting = new CountDownLatch(1), ready = new CountDownLatch(1);
        try {
            SerialLifecycle lifecycle = new SerialLifecycle(worker, new SerialLifecycle.Backend() {
                public void start() { starting.countDown(); await(ready); }
                public void stop() {}
            });
            long owner = lifecycle.start();
            check(starting.await(5, TimeUnit.SECONDS), "start never entered");
            lifecycle.stop(owner);
            equal(SerialLifecycle.State.STOPPING, lifecycle.snapshot().state);
            ready.countDown();
            drain(worker);
            equal(SerialLifecycle.State.STOPPED, lifecycle.snapshot().state);
        } finally { ready.countDown(); worker.shutdownNow(); }
        System.out.println("5 serialized lifecycle checks passed");
    }

    private static SerialLifecycle.Backend backend(List<String> events) {
        return new SerialLifecycle.Backend() {
            public void start() { events.add("start"); }
            public void stop() { events.add("stop"); }
        };
    }
    private static void drain(ExecutorService worker) throws Exception { worker.submit(() -> {}).get(5, TimeUnit.SECONDS); }
    private static void await(CountDownLatch latch) {
        try { check(latch.await(5, TimeUnit.SECONDS), "blocked backend timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void equal(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " got " + actual);
    }
}

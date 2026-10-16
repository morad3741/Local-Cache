package app.localcache;

import java.util.concurrent.Executor;

/** Serializes blocking resource ownership without making callers wait for disk or JNI. */
public final class SerialLifecycle {
    public enum State { STOPPED, STARTING, RUNNING, STOPPING, FAILED }

    public static final class Snapshot {
        public final State state;
        public final long generation;
        public final Exception error;

        private Snapshot(State state, long generation, Exception error) {
            this.state = state;
            this.generation = generation;
            this.error = error;
        }
    }

    public interface Backend {
        void start() throws Exception;
        void stop() throws Exception;
        /** Nonblocking admission flag only; called under the lifecycle monitor. */
        default void setAccepting(boolean accepting) {}
    }

    // Must be a FIFO, single-worker executor. Blocking start/stop never hold this monitor.
    private final Executor executor;
    private final Backend backend;
    private long generation;
    private boolean desired;
    private boolean needsCleanup; // Accessed exclusively by the worker.
    private volatile Snapshot snapshot = new Snapshot(State.STOPPED, 0, null);

    public SerialLifecycle(Executor executor, Backend backend) {
        this.executor = executor;
        this.backend = backend;
    }

    public Snapshot snapshot() { return snapshot; }

    /** Repeated starts share ownership; a restart after a stop gets a new generation. */
    public synchronized long start() {
        if (desired) return generation;
        desired = true;
        long owner = ++generation;
        backend.setAccepting(false);
        snapshot = new Snapshot(State.STARTING, owner, null);
        executor.execute(() -> doStart(owner));
        return owner;
    }

    /** An old service instance must never stop resources owned by its replacement. */
    public synchronized void stop(long owner) {
        if (owner != generation || !desired) return;
        desired = false;
        long stopped = ++generation;
        backend.setAccepting(false);
        snapshot = new Snapshot(State.STOPPING, stopped, null);
        // Never skip an accepted stop: a subsequent start must wait for this barrier.
        executor.execute(() -> doStop(stopped));
    }

    private void doStart(long owner) {
        synchronized (this) {
            if (owner != generation || !desired) return;
        }
        Exception failure = null;
        try {
            if (needsCleanup) {
                backend.stop();
                needsCleanup = false;
            }
            needsCleanup = true;
            backend.start();
        } catch (Exception e) {
            failure = e;
            try { backend.stop(); needsCleanup = false; } catch (Exception cleanup) { e.addSuppressed(cleanup); }
        }
        synchronized (this) {
            if (owner != generation) return;
            if (failure != null) desired = false;
            backend.setAccepting(failure == null);
            snapshot = new Snapshot(failure == null ? State.RUNNING : State.FAILED, owner, failure);
        }
    }

    private void doStop(long stopped) {
        Exception failure = null;
        try { backend.stop(); needsCleanup = false; } catch (Exception e) { failure = e; needsCleanup = true; }
        synchronized (this) {
            if (stopped != generation) return;
            snapshot = new Snapshot(failure == null ? State.STOPPED : State.FAILED, stopped, failure);
        }
    }
}

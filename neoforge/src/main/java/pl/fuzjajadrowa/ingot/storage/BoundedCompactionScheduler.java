package pl.fuzjajadrowa.ingot.storage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

final class BoundedCompactionScheduler implements AutoCloseable {
    private static final int WORKER_COUNT = 1;
    private static final int QUEUE_CAPACITY = 2;
    private static final int DEFERRED_CAPACITY = 128;

    private final Set<Long> pendingRegions = new HashSet<>();
    private final Set<Long> runningRegions = new HashSet<>();
    private final Set<Long> rescheduleRegions = new HashSet<>();
    private final LinkedHashSet<Long> deferredRegions = new LinkedHashSet<>();
    private final Map<Long, Runnable> pendingTasks = new HashMap<>();
    private final Consumer<Throwable> errorReporter;
    private final ThreadPoolExecutor executor;
    private boolean closing;
    private boolean closed;

    BoundedCompactionScheduler(Consumer<Throwable> errorReporter) {
        this.errorReporter = errorReporter;
        this.executor = new ThreadPoolExecutor(WORKER_COUNT, WORKER_COUNT, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
                    Thread thread = new Thread(runnable, "Ingot-Compaction");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    synchronized boolean schedule(long regionKey, Runnable task) {
        if (closing || closed) return false;
        if (!pendingRegions.add(regionKey)) {
            if (runningRegions.contains(regionKey)) rescheduleRegions.add(regionKey);
            return false;
        }

        pendingTasks.put(regionKey, task);
        if (submit(regionKey, task)) return true;
        if (defer(regionKey)) return false;
        pendingRegions.remove(regionKey);
        pendingTasks.remove(regionKey);
        notifyAll();
        return false;
    }

    private boolean defer(long regionKey) {
        if (deferredRegions.contains(regionKey)) return true;
        if (deferredRegions.size() >= DEFERRED_CAPACITY) return false;
        deferredRegions.add(regionKey);
        return true;
    }

    private boolean submit(long regionKey, Runnable task) {
        try {
            executor.execute(() -> {
                synchronized (BoundedCompactionScheduler.this) {
                    runningRegions.add(regionKey);
                }
                try {
                    task.run();
                } catch (Throwable failure) {
                    try {
                        errorReporter.accept(failure);
                    } catch (Throwable reportingFailure) {
                        if (reportingFailure != failure) failure.addSuppressed(reportingFailure);
                        Thread thread = Thread.currentThread();
                        thread.getUncaughtExceptionHandler().uncaughtException(thread, failure);
                    }
                } finally {
                    finishTask(regionKey);
                }
            });
            return true;
        } catch (RejectedExecutionException saturated) {
            return false;
        }
    }

    private void finishTask(long regionKey) {
        synchronized (this) {
            runningRegions.remove(regionKey);
            boolean followUpSubmitted = false;
            boolean followUpRequested = rescheduleRegions.remove(regionKey);
            if (!closed && followUpRequested) {
                Runnable followUp = pendingTasks.get(regionKey);
                if (followUp != null) {
                    followUpSubmitted = submit(regionKey, followUp);
                    if (!followUpSubmitted && !defer(regionKey)) {
                        pendingRegions.remove(regionKey);
                        pendingTasks.remove(regionKey);
                    }
                }
            }

            if (!followUpSubmitted && !deferredRegions.contains(regionKey)) {
                rescheduleRegions.remove(regionKey);
                pendingRegions.remove(regionKey);
                pendingTasks.remove(regionKey);
            }
            notifyAll();
            drainDeferred();
        }
    }

    private void drainDeferred() {
        if (closed || deferredRegions.isEmpty()) return;
        var iterator = deferredRegions.iterator();
        while (iterator.hasNext()) {
            Long key = iterator.next();
            Runnable task = pendingTasks.get(key);
            if (task == null) {
                iterator.remove();
                pendingRegions.remove(key);
                continue;
            }
            if (submit(key, task)) iterator.remove();
            break;
        }
    }

    @Override public void close() {
        synchronized (this) {
            closing = true;
            boolean interrupted = false;
            while (!pendingRegions.isEmpty()) {
                try { wait(); }
                catch (InterruptedException e) { interrupted = true; }
            }
            closed = true;
            executor.shutdown();
            if (interrupted) Thread.currentThread().interrupt();
        }
        boolean interrupted = false;
        while (true) {
            try {
                if (executor.awaitTermination(1, TimeUnit.DAYS)) break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
package pl.fuzjajadrowa.ingot.storage;

import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

final class BoundedCompactionScheduler implements AutoCloseable {
    private static final int WORKER_COUNT = 1;
    private static final int QUEUE_CAPACITY = 2;

    private final Set<Long> pendingRegions = ConcurrentHashMap.newKeySet();
    private final Set<Long> runningRegions = new HashSet<>();
    private final Set<Long> rescheduleRegions = new HashSet<>();
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
            // Bound every write into a single process
            if (runningRegions.contains(regionKey)) rescheduleRegions.add(regionKey);
            return false;
        }
        pendingTasks.put(regionKey, task);
        if (submit(regionKey, task)) return true;
        pendingRegions.remove(regionKey);
        pendingTasks.remove(regionKey);
        return false;
    }

    private boolean submit(long regionKey, Runnable task) {
        try {
            executor.execute(() -> {
                synchronized (BoundedCompactionScheduler.this) { runningRegions.add(regionKey); }
                try {
                    task.run();
                } catch (Throwable failure) {
                    try { errorReporter.accept(failure); }
                    catch (Throwable reportingFailure) {
                        if (reportingFailure != failure) failure.addSuppressed(reportingFailure);
                        Thread thread = Thread.currentThread();
                        thread.getUncaughtExceptionHandler().uncaughtException(thread, failure);
                    }
                } finally {
                    synchronized (BoundedCompactionScheduler.this) {
                        runningRegions.remove(regionKey);
                        if (!closed && rescheduleRegions.remove(regionKey)) {
                            Runnable followUp = pendingTasks.get(regionKey);
                            if (followUp != null && submit(regionKey, followUp)) return;
                            if (followUp != null) rescheduleRegions.add(regionKey);
                        }
                        if (!rescheduleRegions.contains(regionKey)) {
                            pendingRegions.remove(regionKey);
                            pendingTasks.remove(regionKey);
                        }
                        BoundedCompactionScheduler.this.notifyAll();
                        drainDeferred();
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException saturated) {
            return false;
        }
    }

    private void drainDeferred() {
        if (closed) return;
        for (Long key : Set.copyOf(rescheduleRegions)) {
            Runnable task = pendingTasks.get(key);
            if (task == null) { rescheduleRegions.remove(key); continue; }
            if (submit(key, task)) rescheduleRegions.remove(key);
            break;
        }
    }

    @Override public void close() {
        synchronized (this) {
            if (!closing) closing = true;
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
package pl.fuzjajadrowa.ingot.storage;

import java.util.Set;
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
    private final Consumer<Throwable> errorReporter;
    private final ThreadPoolExecutor executor;
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
        if (closed || !pendingRegions.add(regionKey)) return false;
        try {
            executor.execute(() -> {
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
                    pendingRegions.remove(regionKey);
                }
            });
            return true;
        } catch (RejectedExecutionException saturated) {
            pendingRegions.remove(regionKey);
            return false;
        }
    }

    @Override public void close() {
        synchronized (this) {
            if (!closed) {
                closed = true;
                executor.shutdown();
            }
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
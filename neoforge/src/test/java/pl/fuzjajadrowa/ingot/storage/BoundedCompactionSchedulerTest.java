package pl.fuzjajadrowa.ingot.storage;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class BoundedCompactionSchedulerTest {
    @Test public void deduplicatesRegionsAndRejectsWorkWhenBoundedQueueIsFull() throws Exception {
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger completed = new AtomicInteger();
        BoundedCompactionScheduler scheduler = new BoundedCompactionScheduler(failure -> fail(failure.toString()));
        try {
            assertTrue(scheduler.schedule(1, () -> {
                running.countDown();
                try { release.await(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                completed.incrementAndGet();
            }));
            assertTrue(running.await(5, TimeUnit.SECONDS));
            assertFalse(scheduler.schedule(1, completed::incrementAndGet));
            assertTrue(scheduler.schedule(2, completed::incrementAndGet));
            assertTrue(scheduler.schedule(3, completed::incrementAndGet));
            assertFalse(scheduler.schedule(4, completed::incrementAndGet));
        } finally {
            release.countDown();
            scheduler.close();
        }
        assertEquals(3, completed.get());
        assertFalse(scheduler.schedule(5, completed::incrementAndGet));
    }

    @Test public void reportsTaskFailuresAndContinuesProcessing() {
        CountDownLatch reported = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        BoundedCompactionScheduler scheduler = new BoundedCompactionScheduler(failure -> reported.countDown());
        try {
            assertTrue(scheduler.schedule(1, () -> { throw new IllegalStateException("injected compaction error"); }));
            assertTrue(scheduler.schedule(2, completed::countDown));
            assertTrue(reported.await(5, TimeUnit.SECONDS));
            assertTrue(completed.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e.toString());
        } finally { scheduler.close(); }
    }
}
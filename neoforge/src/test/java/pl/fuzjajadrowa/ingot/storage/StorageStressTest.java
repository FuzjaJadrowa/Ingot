package pl.fuzjajadrowa.ingot.storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import pl.fuzjajadrowa.ingot.compression.CompressionRegistry;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class StorageStressTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test(timeout = 90_000) public void concurrentWritesDeletesReadsEvictionsAndCompactionsKeepFinalState() throws Exception {
        Path directory = temporary.newFolder("stress").toPath();
        StorageOptions options = new StorageOptions(64 * 1024, 64 * 1024, 2, false);
        IngotStorage storage = new IngotStorage(directory, options, CompressionRegistry.defaults(), ignored -> { });
        int workers = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers + 1);
        @SuppressWarnings("unchecked") Map<Integer, byte[]>[] finalValues = new Map[workers];
        try {
            @SuppressWarnings("unchecked")
            Future<Map<Integer, byte[]>>[] futures = new Future[workers];
            for (int worker = 0; worker < workers; worker++) {
                int id = worker;
                futures[worker] = executor.submit(() -> {
                    start.await();
                    Random random = new Random(0x5eedL + id);
                    Map<Integer, byte[]> expected = new HashMap<>();
                    int baseX = id * 32;
                    for (int operation = 0; operation < 240; operation++) {
                        int slot = random.nextInt(16);
                        int x = baseX + slot;
                        int z = random.nextInt(16);
                        if (random.nextInt(5) == 0) {
                            storage.write(x, z, null);
                            expected.remove((x & 31) | (z << 5));
                        } else {
                            byte[] payload = new byte[24 + random.nextInt(512)];
                            random.nextBytes(payload);
                            storage.write(x, z, payload);
                            expected.put((x & 31) | (z << 5), payload);
                            assertArrayEquals(payload, storage.read(x, z));
                        }
                    }
                    storage.flush();
                    return expected;
                });
            }
            Future<?> compactions = executor.submit(() -> {
                start.await();
                for (int round = 0; round < 12; round++) {
                    for (int worker = 0; worker < workers; worker++) storage.compactRegion(worker * 32, 0);
                }
                return null;
            });
            start.countDown();
            for (int worker = 0; worker < workers; worker++) {
                Map<Integer, byte[]> expected = futures[worker].get(60, TimeUnit.SECONDS);
                finalValues[worker] = expected;
                for (int slot = 0; slot < 16; slot++) {
                    for (int z = 0; z < 16; z++) {
                        byte[] value = expected.get(slot | (z << 5));
                        assertArrayEquals("worker=" + worker + " slot=" + slot + " z=" + z,
                                value, storage.read(worker * 32 + slot, z));
                    }
                }
            }
            compactions.get(60, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue("worker threads did not terminate", executor.awaitTermination(10, TimeUnit.SECONDS));
            storage.close();
        }
        try (IngotStorage reopened = new IngotStorage(directory, options, CompressionRegistry.defaults(), ignored -> { })) {
            for (int worker = 0; worker < workers; worker++) {
                for (int slot = 0; slot < 16; slot++) {
                    for (int z = 0; z < 16; z++) {
                        assertArrayEquals("reopen worker=" + worker + " slot=" + slot + " z=" + z,
                                finalValues[worker].get(slot | (z << 5)),
                                reopened.read(worker * 32 + slot, z));
                    }
                }
            }
        }
    }

    @Test(timeout = 30_000) public void concurrentProducersOverwritingOneChunkLeaveOneCompleteSubmittedValue() throws Exception {
        Path directory = temporary.newFolder("same-chunk").toPath();
        IngotStorage storage = new IngotStorage(directory, StorageOptions.defaults(false),
                CompressionRegistry.defaults(), ignored -> { });
        int producers = 4, writesPerProducer = 120;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(producers);
        Set<Long> submittedVersions = ConcurrentHashMap.newKeySet();
        try {
            @SuppressWarnings("unchecked") Future<?>[] futures = new Future<?>[producers];
            for (int producer = 0; producer < producers; producer++) {
                int id = producer;
                futures[producer] = executor.submit(() -> {
                    Random random = new Random(0xc0111deL + id);
                    start.await();
                    for (int sequence = 0; sequence < writesPerProducer; sequence++) {
                        byte[] payload = new byte[256];
                        random.nextBytes(payload);
                        long version = ((long) id << 32) | Integer.toUnsignedLong(sequence);
                        java.nio.ByteBuffer.wrap(payload).putLong(version);
                        submittedVersions.add(version);
                        storage.write(7, 9, payload);
                        byte[] visible = storage.read(7, 9);
                        assertNotNull(visible);
                        assertTrue("read must observe a complete submitted version",
                                submittedVersions.contains(java.nio.ByteBuffer.wrap(visible).getLong()));
                    }
                    return null;
                });
            }
            start.countDown();
            for (Future<?> future : futures) future.get(20, TimeUnit.SECONDS);
            storage.flush();
            byte[] finalValue = storage.read(7, 9);
            assertTrue(submittedVersions.contains(java.nio.ByteBuffer.wrap(finalValue).getLong()));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            storage.close();
        }
        try (IngotStorage reopened = new IngotStorage(directory, StorageOptions.defaults(false),
                CompressionRegistry.defaults(), ignored -> { })) {
            byte[] persisted = reopened.read(7, 9);
            assertTrue(submittedVersions.contains(java.nio.ByteBuffer.wrap(persisted).getLong()));
        }
    }
}
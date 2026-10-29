package pl.fuzjajadrowa.ingot.storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import pl.fuzjajadrowa.ingot.compression.CompressionRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.*;

public class IngotStorageTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void rejectsIoAfterCloseAndReleasesRegionForReopen() throws Exception {
        Path directory = temporary.getRoot().toPath().resolve("closed");
        IngotStorage storage = new IngotStorage(directory, StorageOptions.defaults(false), CompressionRegistry.defaults(), ignored -> { });
        storage.write(0, 0, new byte[] {7});
        storage.close();
        try { storage.read(0, 0); fail("Closed storage must reject reads"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("closed")); }
        try (IngotStorage reopened = new IngotStorage(directory, StorageOptions.defaults(false), CompressionRegistry.defaults(), ignored -> { })) {
            assertArrayEquals(new byte[] {7}, reopened.read(0, 0));
        }
    }

    @Test public void evictsAndReopensCachedRegionsWithoutLosingWrites() throws Exception {
        Path directory = temporary.getRoot().toPath().resolve("ingot");
        StorageOptions options = new StorageOptions(1024, 1024, 1, false);
        try (IngotStorage storage = new IngotStorage(directory, options, CompressionRegistry.defaults(), ignored -> { })) {
            storage.write(0, 0, new byte[]{10});
            storage.write(32, 0, new byte[]{20});
            assertArrayEquals(new byte[]{10}, storage.read(0, 0));
            assertArrayEquals(new byte[]{20}, storage.read(32, 0));
            storage.flush();
        }
        assertTrue(Files.exists(directory.resolve("r.0.0.ir")));
        assertTrue(Files.exists(directory.resolve("r.1.0.ir")));
    }

    @Test public void persistsOverwritesAndNegativeCoordinatesAcrossReopen() throws Exception {
        Path directory = temporary.getRoot().toPath().resolve("negative-coordinates");
        try (IngotStorage storage = new IngotStorage(directory, StorageOptions.defaults(false),
                CompressionRegistry.defaults(), ignored -> { })) {
            storage.write(-1, -33, new byte[] {1});
            storage.write(-32, -64, new byte[] {2});
            storage.write(-1, -33, new byte[] {3, 4});
            storage.write(0, -33, new byte[] {5});
            assertArrayEquals(new byte[] {3, 4}, storage.read(-1, -33));
            assertArrayEquals(new byte[] {2}, storage.read(-32, -64));
            assertArrayEquals(new byte[] {5}, storage.read(0, -33));
            storage.flush();
        }
        try (IngotStorage reopened = new IngotStorage(directory, StorageOptions.defaults(false),
                CompressionRegistry.defaults(), ignored -> { })) {
            assertArrayEquals(new byte[] {3, 4}, reopened.read(-1, -33));
            assertArrayEquals(new byte[] {2}, reopened.read(-32, -64));
            assertArrayEquals(new byte[] {5}, reopened.read(0, -33));
        }
    }

    @Test public void permitsConcurrentOperationsAcrossRegionsAndChunks() throws Exception {
        Path directory = temporary.getRoot().toPath().resolve("ingot");
        StorageOptions options = new StorageOptions(4096, 4096, 4, false);
        IngotStorage storage = new IngotStorage(directory, options, CompressionRegistry.defaults(), ignored -> { });
        int workers = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int worker = 0; worker < workers; worker++) {
                int id = worker;
                tasks.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < 20; i++) {
                        int x = (id & 1) * 32 + i;
                        int z = id * 32;
                        byte[] payload = {(byte) id, (byte) i};
                        storage.write(x, z, payload);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) task.get();
            for (int worker = 0; worker < workers; worker++) {
                for (int i = 0; i < 20; i++) {
                    int x = (worker & 1) * 32 + i;
                    int z = worker * 32;
                    assertArrayEquals(new byte[]{(byte) worker, (byte) i}, storage.read(x, z));
                }
            }
        } finally {
            executor.shutdownNow();
            storage.close();
        }
    }

    @Test public void coordinatesConcurrentEvictionOfTheSameRegionHandles() throws Exception {
        Path directory = temporary.getRoot().toPath().resolve("ingot");
        IngotStorage storage = new IngotStorage(directory,
                new StorageOptions(1024, 1024, 1, false), CompressionRegistry.defaults(), ignored -> { });
        int workers = 4;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int worker = 0; worker < workers; worker++) {
                int id = worker;
                tasks.add(executor.submit(() -> {
                    start.await();
                    for (int i = 0; i < 20; i++) {
                        int regionX = ((id + i) & 1) * 32;
                        storage.write(regionX + i, id * 8, new byte[]{(byte) id, (byte) i});
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> task : tasks) task.get();
            for (int worker = 0; worker < workers; worker++) {
                for (int i = 0; i < 20; i++) {
                    int regionX = ((worker + i) & 1) * 32;
                    assertArrayEquals(new byte[]{(byte) worker, (byte) i},
                            storage.read(regionX + i, worker * 8));
                }
            }
        } finally {
            executor.shutdownNow();
            storage.close();
        }
    }

    @Test public void concurrentCompactionAndWritesPreserveCommittedPayloads() throws Exception {
        Path directory = temporary.getRoot().toPath().resolve("concurrent-compaction");
        IngotStorage storage = new IngotStorage(directory, StorageOptions.defaults(false),
                CompressionRegistry.defaults(), ignored -> { });
        storage.write(0, 0, new byte[] {0, 11});
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> compaction = executor.submit(() -> {
                start.await();
                storage.compactRegion(0, 0);
                return null;
            });
            Future<?> writes = executor.submit(() -> {
                start.await();
                for (int x = 0; x < 24; x++) storage.write(x, 0, new byte[] {(byte) x, 11});
                for (int value = 0; value < 100; value++) {
                    storage.write(31, 31, new byte[] {(byte) value, (byte) (value >>> 1)});
                }
                return null;
            });
            start.countDown();
            compaction.get();
            writes.get();

            for (int x = 0; x < 24; x++) assertArrayEquals(new byte[] {(byte) x, 11}, storage.read(x, 0));
            assertArrayEquals(new byte[] {99, 49}, storage.read(31, 31));
            storage.flush();
        } finally {
            executor.shutdownNow();
            storage.close();
        }

        try (IngotStorage reopened = new IngotStorage(directory, StorageOptions.defaults(false),
                CompressionRegistry.defaults(), ignored -> { })) {
            for (int x = 0; x < 24; x++) assertArrayEquals(new byte[] {(byte) x, 11}, reopened.read(x, 0));
            assertArrayEquals(new byte[] {99, 49}, reopened.read(31, 31));
        }
    }

    @Test public void automaticallyCompactsRecommendedRegionAndWaitsOnClose() throws Exception {
        Path directory = temporary.getRoot().toPath().resolve("ingot");
        StorageOptions options = new StorageOptions(2 * 1024 * 1024, 2 * 1024 * 1024, 2, false);
        java.util.concurrent.atomic.AtomicReference<Throwable> reported = new java.util.concurrent.atomic.AtomicReference<>();
        IngotStorage storage = new IngotStorage(directory, options, CompressionRegistry.defaults(), ignored -> { },
                reported::set);
        byte[] payload = new byte[1_100_000];
        new java.util.Random(901).nextBytes(payload);
        storage.write(0, 0, payload);
        storage.write(0, 0, payload);
        storage.close();
        assertNull(reported.get());
        try (IngotRegionFile compacted = new IngotRegionFile(directory.resolve("r.0.0.ir"), 0, 0,
                options, CompressionRegistry.defaults())) {
            assertEquals(1, compacted.statistics().committedRecords());
            assertArrayEquals(payload, compacted.read(0, 0));
        }
    }

    @Test public void removesOrphanCandidateOnlyAfterActiveRegionValidates() throws Exception {
        Path directory = temporary.getRoot().toPath().resolve("ingot");
        Files.createDirectories(directory);
        Path regionPath = directory.resolve("r.0.0.ir");
        try (IngotRegionFile region = new IngotRegionFile(regionPath, 0, 0,
                new StorageOptions(1024, 1024, 2, false), CompressionRegistry.defaults())) {
            region.write(0, 0, new byte[]{7, 8, 9});
        }
        Path candidate = directory.resolve("r.0.0.ir.compacting-interrupted");
        Path candidateLock = candidate.resolveSibling(candidate.getFileName() + ".lock");
        Files.write(candidate, new byte[]{1, 2, 3});
        Files.write(candidateLock, new byte[0]);

        try (IngotStorage storage = new IngotStorage(directory,
                new StorageOptions(1024, 1024, 2, false), CompressionRegistry.defaults(), ignored -> { })) {
            assertArrayEquals(new byte[]{7, 8, 9}, storage.read(0, 0));
        }
        assertFalse(Files.exists(candidate));
        assertFalse(Files.exists(candidateLock));
    }

    @Test public void missingActiveRegionDoesNotFallBackToCandidate() throws Exception {
        Path directory = temporary.getRoot().toPath().resolve("ingot");
        Files.createDirectories(directory);
        Path orphan = directory.resolve("r.0.0.ir.compacting-orphan");
        Files.write(orphan, new byte[]{1, 2, 3});
        try (IngotStorage storage = new IngotStorage(directory,
                new StorageOptions(1024, 1024, 2, false), CompressionRegistry.defaults(), ignored -> { })) {
            try {
                storage.read(0, 0);
                fail("Missing active region with a candidate must be an explicit recovery error");
            } catch (IOException expected) { assertTrue(expected.getMessage().contains("recovery required")); }
        }
        assertTrue(Files.exists(orphan));
        assertFalse(Files.exists(directory.resolve("r.0.0.ir")));
    }

    @Test public void damagedActiveRegionPreservesCandidateForInvestigation() throws Exception {
        Path directory = temporary.getRoot().toPath().resolve("ingot");
        Files.createDirectories(directory);
        Path regionPath = directory.resolve("r.0.0.ir");
        try (IngotRegionFile region = new IngotRegionFile(regionPath, 0, 0,
                new StorageOptions(1024, 1024, 2, false), CompressionRegistry.defaults())) {
            region.write(0, 0, new byte[]{4});
        }
        byte[] header = Files.readAllBytes(regionPath);
        header[0] ^= 1;
        Files.write(regionPath, header);
        Path orphan = directory.resolve("r.0.0.ir.compacting-possible-recovery");
        Files.write(orphan, new byte[]{5, 6, 7});

        try (IngotStorage storage = new IngotStorage(directory,
                new StorageOptions(1024, 1024, 2, false), CompressionRegistry.defaults(), ignored -> { })) {
            try {
                storage.read(0, 0);
                fail("Damaged active region must fail rather than fall back");
            } catch (CorruptRegionException expected) { }
        }
        assertTrue(Files.exists(orphan));
    }
}
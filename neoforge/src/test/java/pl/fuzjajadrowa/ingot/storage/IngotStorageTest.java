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

    @Test public void operationsAfterCloseFailAndCloseIsIdempotent() throws Exception {
        IngotStorage storage = new IngotStorage(temporary.getRoot().toPath(),
                new StorageOptions(1024, 1024, 2, false), CompressionRegistry.defaults(), ignored -> { });
        storage.write(0, 0, new byte[]{1});
        storage.close();
        storage.close();
        try {
            storage.read(0, 0);
            fail("Reads after close must fail");
        } catch (IOException expected) { }
    }
}
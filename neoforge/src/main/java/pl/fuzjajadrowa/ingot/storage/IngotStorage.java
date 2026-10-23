package pl.fuzjajadrowa.ingot.storage;

import pl.fuzjajadrowa.ingot.compression.CompressionRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

public final class IngotStorage implements AutoCloseable {
    private static final class RegionHandle {
        private final IngotRegionFile file;
        private int activeOperations;

        private RegionHandle(IngotRegionFile file) { this.file = file; }
    }

    private final Path directory;
    private final StorageOptions options;
    private final CompressionRegistry codecs;
    private final Consumer<Path> recoveryReporter;
    private final Map<Long, RegionHandle> regions = new LinkedHashMap<>(16, 0.75f, true);
    private final ReentrantLock cacheLock = new ReentrantLock();
    private final Condition stateChanged = cacheLock.newCondition();
    private boolean barrier;
    private boolean closed;
    private int activeOperations;

    public IngotStorage(Path directory, StorageOptions options, CompressionRegistry codecs, Consumer<Path> recoveryReporter) {
        this.directory = directory;
        this.options = options;
        this.codecs = codecs;
        this.recoveryReporter = recoveryReporter;
    }

    private RegionHandle acquire(int x, int z, boolean create) throws IOException {
        cacheLock.lock();
        try {
            while (barrier) awaitStateChange();
            if (closed) throw new IOException("Storage closed: " + directory);
            int rx = x >> 5, rz = z >> 5;
            long key = ((long) rx << 32) | Integer.toUnsignedLong(rz);
            RegionHandle handle = regions.get(key);
            if (handle == null) {
                Path path = directory.resolve("r." + rx + "." + rz + ".ir");
                if (!create && Files.notExists(path)) return null;
                while (regions.size() >= options.cachedRegions()) {
                    while (barrier) awaitStateChange();
                    if (closed) throw new IOException("Storage closed: " + directory);
                    RegionHandle evicted = evictIdleRegion();
                    if (evicted == null) {
                        awaitStateChange();
                        if (closed) throw new IOException("Storage closed: " + directory);
                    } else {
                        cacheLock.unlock();
                        try { evicted.file.close(); }
                        finally { cacheLock.lock(); }
                    }
                }
                if (create) Files.createDirectories(directory);
                IngotRegionFile file = new IngotRegionFile(path, rx, rz, options, codecs);
                handle = new RegionHandle(file);
                regions.put(key, handle);
                if (file.recoveredTail() != null) recoveryReporter.accept(file.recoveredTail());
            }
            handle.activeOperations++;
            activeOperations++;
            return handle;
        } finally {
            cacheLock.unlock();
        }
    }

    private RegionHandle evictIdleRegion() {
        Iterator<Map.Entry<Long, RegionHandle>> iterator = regions.entrySet().iterator();
        while (iterator.hasNext()) {
            RegionHandle candidate = iterator.next().getValue();
            if (candidate.activeOperations == 0) {
                iterator.remove();
                return candidate;
            }
        }
        return null;
    }

    private void release(RegionHandle handle) {
        cacheLock.lock();
        try {
            handle.activeOperations--;
            activeOperations--;
            stateChanged.signalAll();
        } finally {
            cacheLock.unlock();
        }
    }

    private void awaitStateChange() throws IOException {
        try {
            stateChanged.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for region storage", e);
        }
    }

    public byte[] read(int x, int z) throws IOException {
        RegionHandle handle = acquire(x, z, false);
        if (handle == null) return null;
        try { return handle.file.read(x, z); }
        finally { release(handle); }
    }

    public boolean contains(int x, int z) throws IOException {
        RegionHandle handle = acquire(x, z, false);
        if (handle == null) return false;
        try { return handle.file.contains(x, z); }
        finally { release(handle); }
    }

    public void write(int x, int z, byte[] payload) throws IOException {
        RegionHandle handle = acquire(x, z, payload != null);
        if (handle == null) return;
        try { handle.file.write(x, z, payload); }
        finally { release(handle); }
    }

    public void flush() throws IOException {
        List<IngotRegionFile> files;
        boolean ownsBarrier = false;
        cacheLock.lock();
        try {
            while (barrier) awaitStateChange();
            if (closed) throw new IOException("Storage closed: " + directory);
            barrier = true;
            ownsBarrier = true;
            while (activeOperations != 0) awaitStateChange();
            files = regions.values().stream().map(handle -> handle.file).toList();
        } catch (IOException e) {
            if (ownsBarrier) {
                barrier = false;
                stateChanged.signalAll();
            }
            throw e;
        } finally {
            cacheLock.unlock();
        }

        IOException failure = null;
        try {
            for (IngotRegionFile file : files) {
                try { file.flush(); } catch (IOException e) { failure = IngotRegionFile.collect(failure, e); }
            }
        } finally {
            cacheLock.lock();
            try {
                barrier = false;
                stateChanged.signalAll();
            } finally {
                cacheLock.unlock();
            }
        }
        if (failure != null) throw failure;
    }

    @Override public void close() throws IOException {
        List<IngotRegionFile> files;
        cacheLock.lock();
        try {
            while (barrier) awaitStateChange();
            if (closed) return;
            closed = true;
            barrier = true;
            boolean interrupted = false;
            while (activeOperations != 0) {
                try { stateChanged.await(); }
                catch (InterruptedException e) { interrupted = true; }
            }
            if (interrupted) Thread.currentThread().interrupt();
            files = new ArrayList<>(regions.size());
            for (RegionHandle handle : regions.values()) files.add(handle.file);
            regions.clear();
        } finally {
            cacheLock.unlock();
        }

        IOException failure = null;
        try {
            for (IngotRegionFile file : files) {
                try { file.close(); } catch (IOException e) { failure = IngotRegionFile.collect(failure, e); }
            }
        } finally {
            cacheLock.lock();
            try {
                barrier = false;
                stateChanged.signalAll();
            } finally {
                cacheLock.unlock();
            }
        }
        if (failure != null) throw failure;
    }
}
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
import java.util.UUID;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import java.util.function.Consumer;

public final class IngotStorage implements AutoCloseable {
    private static final System.Logger LOGGER = System.getLogger(IngotStorage.class.getName());

    private static final class RegionHandle {
        private final IngotRegionFile file;
        private int activeOperations;

        private RegionHandle(IngotRegionFile file) { this.file = file; }
    }

    private final Path directory;
    private final StorageOptions options;
    private final CompressionRegistry codecs;
    private final Consumer<Path> recoveryReporter;
    private final Consumer<Throwable> compactionErrorReporter;
    private final BoundedCompactionScheduler compactionScheduler;
    private final Map<Long, RegionHandle> regions = new LinkedHashMap<>(16, 0.75f, true);
    private final java.util.Set<Long> openingRegions = new java.util.HashSet<>();
    private final ReentrantLock cacheLock = new ReentrantLock();
    private final Condition stateChanged = cacheLock.newCondition();
    private boolean barrier;
    private boolean closed;
    private int activeOperations;

    public IngotStorage(Path directory, StorageOptions options, CompressionRegistry codecs, Consumer<Path> recoveryReporter) {
        this(directory, options, codecs, recoveryReporter, failure ->
                LOGGER.log(System.Logger.Level.ERROR, "Background Ingot compaction failed", failure));
    }

    public IngotStorage(Path directory, StorageOptions options, CompressionRegistry codecs,
                        Consumer<Path> recoveryReporter, Consumer<Throwable> compactionErrorReporter) {
        this.directory = directory;
        this.options = options;
        this.codecs = codecs;
        this.recoveryReporter = recoveryReporter;
        this.compactionErrorReporter = compactionErrorReporter;
        this.compactionScheduler = new BoundedCompactionScheduler(this::reportCompactionFailure);
    }

    private RegionHandle acquire(int x, int z, boolean create) throws IOException {
        long openingKey = Long.MIN_VALUE;
        boolean reservedOpen = false;
        cacheLock.lock();
        try {
            while (barrier) awaitStateChange();
            if (closed) throw new IOException("Storage closed: " + directory);
            int rx = x >> 5, rz = z >> 5;
            long key = ((long) rx << 32) | Integer.toUnsignedLong(rz);
            while (barrier) awaitStateChange();
            if (closed) throw new IOException("Storage closed: " + directory);
            RegionHandle handle = regions.get(key);
            if (handle == null) {
                Path path = directory.resolve("r." + rx + "." + rz + ".ir");
                while (openingRegions.contains(key)) {
                    awaitStateChange();
                    if (closed) throw new IOException("Storage closed: " + directory);
                    handle = regions.get(key);
                    if (handle != null) break;
                }
                List<Path> candidates = List.of();
                if (handle == null) {
                    openingRegions.add(key);
                    openingKey = key;
                    reservedOpen = true;
                    cacheLock.unlock();
                    try { candidates = compactionCandidates(path); }
                    finally {
                        cacheLock.lock();
                    }
                    if (closed) throw new IOException("Storage closed: " + directory);
                    handle = regions.get(key);
                }
                if (handle == null) {
                if (Files.notExists(path)) {
                    if (!candidates.isEmpty()) {
                        throw new IOException("Compaction recovery required for " + path
                                + "; active region is missing and candidate files were preserved: " + candidates);
                    }
                    if (!create) {
                        openingRegions.remove(openingKey);
                        reservedOpen = false;
                        stateChanged.signalAll();
                        return null;
                    }
                }
                while (regions.size() >= options.cachedRegions()) {
                    if (closed) throw new IOException("Storage closed: " + directory);
                    Map.Entry<Long, RegionHandle> evicted = evictIdleRegion();
                    if (evicted == null) {
                        awaitStateChange();
                        if (closed) throw new IOException("Storage closed: " + directory);
                    } else {
                        evicted.getValue().file.close();
                    }
                }
                if (closed) throw new IOException("Storage closed: " + directory);
                handle = regions.get(key);
                if (handle == null) {
                    if (create) Files.createDirectories(directory);
                    IngotRegionFile file = new IngotRegionFile(path, rx, rz, options, codecs);
                    handle = new RegionHandle(file);
                    regions.put(key, handle);
                    if (file.recoveredTail() != null) recoveryReporter.accept(file.recoveredTail());
                    discardOrphanCandidates(path, candidates);
                }
                openingRegions.remove(openingKey);
                reservedOpen = false;
                stateChanged.signalAll();
                }
            }
            handle.activeOperations++;
            activeOperations++;
            return handle;
        } finally {
            if (reservedOpen) {
                openingRegions.remove(openingKey);
                stateChanged.signalAll();
            }
            cacheLock.unlock();
        }
    }

    private List<Path> compactionCandidates(Path regionPath) throws IOException {
        if (Files.notExists(directory)) return List.of();
        String prefix = regionPath.getFileName() + ".compacting-";
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.filter(entry -> {
                String name = entry.getFileName().toString();
                return name.startsWith(prefix) && !name.endsWith(".lock");
            }).toList();
        }
    }

    private void discardOrphanCandidates(Path regionPath, List<Path> candidates) {
        for (Path candidate : candidates) {
            try {
                Files.deleteIfExists(candidate);
                Files.deleteIfExists(candidate.resolveSibling(candidate.getFileName() + ".lock"));
                LOGGER.log(System.Logger.Level.WARNING,
                        "Removed stale compaction candidate because the active region validated: " + candidate);
            } catch (IOException cleanupFailure) {
                reportCompactionFailure(new IOException("Could not clean stale compaction candidate for "
                        + regionPath + ": " + candidate, cleanupFailure));
            }
        }
    }

    private Map.Entry<Long, RegionHandle> evictIdleRegion() {
        Iterator<Map.Entry<Long, RegionHandle>> iterator = regions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, RegionHandle> entry = iterator.next();
            RegionHandle candidate = entry.getValue();
            if (candidate.activeOperations == 0) {
                Map.Entry<Long, RegionHandle> removed = Map.entry(entry.getKey(), candidate);
                iterator.remove();
                return removed;
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
        try {
            handle.file.write(x, z, payload);
            try {
                if (handle.file.statistics().compactionRecommended()) {
                    int regionX = x >> 5, regionZ = z >> 5;
                    long key = ((long) regionX << 32) | Integer.toUnsignedLong(regionZ);
                    compactionScheduler.schedule(key, () -> {
                        try { compactRegion(regionX << 5, regionZ << 5); }
                        catch (IOException e) { throw new java.io.UncheckedIOException(e); }
                    });
                }
            } catch (IOException statisticsFailure) {
                reportCompactionFailure(statisticsFailure);
            }
        }
        finally { release(handle); }
    }

    void compactRegion(int x, int z) throws IOException {
        RegionHandle handle = acquire(x, z, false);
        if (handle == null) return;
        int regionX = x >> 5, regionZ = z >> 5;
        Path regionPath = directory.resolve("r." + regionX + "." + regionZ + ".ir");
        Path candidate = directory.resolve(regionPath.getFileName() + ".compacting-" + UUID.randomUUID());
        try { handle.file.compactTo(candidate); }
        finally { release(handle); }
    }

    private void reportCompactionFailure(Throwable failure) {
        try { compactionErrorReporter.accept(failure); }
        catch (Throwable reportingFailure) {
            if (reportingFailure != failure) failure.addSuppressed(reportingFailure);
            LOGGER.log(System.Logger.Level.ERROR, "Unable to report Ingot compaction failure", failure);
        }
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
            while (activeOperations != 0 || !openingRegions.isEmpty()) awaitStateChange();
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
        compactionScheduler.close();
        List<IngotRegionFile> files;
        cacheLock.lock();
        try {
            while (barrier) awaitStateChange();
            if (closed) return;
            closed = true;
            barrier = true;
            boolean interrupted = false;
            while (activeOperations != 0 || !openingRegions.isEmpty()) {
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
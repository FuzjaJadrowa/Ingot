package pl.fuzjajadrowa.ingot.storage;

import pl.fuzjajadrowa.ingot.compression.CompressionRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class IngotStorage implements AutoCloseable {
    private final Path directory;
    private final StorageOptions options;
    private final CompressionRegistry codecs;
    private final Consumer<Path> recoveryReporter;
    private final Map<Long, IngotRegionFile> regions = new LinkedHashMap<>(16, 0.75f, true);
    private boolean closed;

    public IngotStorage(Path directory, StorageOptions options, CompressionRegistry codecs, Consumer<Path> recoveryReporter) {
        this.directory = directory;
        this.options = options;
        this.codecs = codecs;
        this.recoveryReporter = recoveryReporter;
    }

    private IngotRegionFile region(int x, int z, boolean create) throws IOException {
        if (closed) throw new IOException("Storage closed: " + directory);
        int rx = x >> 5, rz = z >> 5;
        long key = ((long) rx << 32) | Integer.toUnsignedLong(rz);
        IngotRegionFile region = regions.get(key);
        if (region != null) return region;
        Path path = directory.resolve("r." + rx + "." + rz + ".ir");
        if (!create && Files.notExists(path)) return null;
        if (regions.size() >= options.cachedRegions()) {
            var it = regions.entrySet().iterator();
            IngotRegionFile oldest = it.next().getValue();
            it.remove();
            oldest.close();
        }
        if (create) Files.createDirectories(directory);
        region = new IngotRegionFile(path, rx, rz, options, codecs);
        regions.put(key, region);
        if (region.recoveredTail() != null) recoveryReporter.accept(region.recoveredTail());
        return region;
    }

    public synchronized byte[] read(int x, int z) throws IOException {
        IngotRegionFile region = region(x, z, false);
        return region == null ? null : region.read(x, z);
    }
    public synchronized boolean contains(int x, int z) throws IOException {
        IngotRegionFile region = region(x, z, false);
        return region != null && region.contains(x, z);
    }
    public synchronized void write(int x, int z, byte[] payload) throws IOException {
        IngotRegionFile region = region(x, z, payload != null);
        if (region != null) region.write(x, z, payload);
    }
    public synchronized void flush() throws IOException {
        if (closed) throw new IOException("Storage closed: " + directory);
        IOException failure = null;
        for (IngotRegionFile region : regions.values()) {
            try { region.flush(); } catch (IOException e) { failure = IngotRegionFile.collect(failure, e); }
        }
        if (failure != null) throw failure;
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = null;
        for (IngotRegionFile region : regions.values()) {
            try { region.close(); } catch (IOException e) { failure = IngotRegionFile.collect(failure, e); }
        }
        regions.clear();
        if (failure != null) throw failure;
    }
}
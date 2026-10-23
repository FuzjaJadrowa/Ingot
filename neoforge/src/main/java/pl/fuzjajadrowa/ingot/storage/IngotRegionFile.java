package pl.fuzjajadrowa.ingot.storage;

import pl.fuzjajadrowa.ingot.compression.CompressionRegistry;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.zip.CRC32C;

public final class IngotRegionFile implements AutoCloseable {
    public static final int HEADER_SIZE = 64;
    public static final int RECORD_HEADER_SIZE = 48;
    public static final int COMMIT_SIZE = 24;
    private static final int MAGIC = 0x494e4754; // INGT
    private static final int RECORD_MAGIC = 0x49524344; // IRCD
    private static final int COMMIT_MAGIC = 0x49434d54; // ICMT
    private final Path path;
    private final int regionX;
    private final int regionZ;
    private final StorageOptions options;
    private final CompressionRegistry codecs;
    private final FileChannel channel;
    private final FileLock lock;
    private final Entry[] index = new Entry[1024];
    private long end = HEADER_SIZE;
    private long generation;
    private long committedRecords;
    private boolean dirty;
    private boolean closed;
    private IOException writeFailure;
    private Path recoveredTail;

    private record Entry(long offset, int length, int rawLength, int rawCrc, int codec) {}

    public IngotRegionFile(Path path, int regionX, int regionZ, StorageOptions options,
                           CompressionRegistry codecs) throws IOException {
        this.path = path;
        this.regionX = regionX;
        this.regionZ = regionZ;
        this.options = options;
        this.codecs = codecs;
        FileChannel opened;
        boolean created;
        try {
            opened = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE);
            created = true;
        } catch (FileAlreadyExistsException e) {
            opened = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            created = false;
        }
        FileLock acquired = null;
        try {
            try { acquired = opened.tryLock(); }
            catch (OverlappingFileLockException e) { throw new IOException("Region already open: " + path, e); }
            if (acquired == null) throw new IOException("Region locked by another process: " + path);
            this.channel = opened;
            this.lock = acquired;
            if (created) initialize();
            else validateHeader();
            restoreIndex();
        } catch (IOException | RuntimeException | Error e) {
            if (acquired != null) try { acquired.release(); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
            try { opened.close(); } catch (IOException suppressed) { e.addSuppressed(suppressed); }
            throw e;
        }
    }

    private static ByteBuffer buffer(int size) { return ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN); }

    private void initialize() throws IOException {
        UUID id = UUID.randomUUID();
        ByteBuffer header = buffer(HEADER_SIZE);
        header.putInt(MAGIC).putInt(1).putInt(HEADER_SIZE).putInt(regionX).putInt(regionZ).putInt(0);
        header.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        header.position(60);
        header.putInt(crc(header.array(), 0, 60));
        header.flip();
        writeFully(header, 0);
        channel.force(true);
    }

    private void validateHeader() throws IOException {
        if (channel.size() < HEADER_SIZE) throw corrupt("Truncated region header");
        ByteBuffer header = readBuffer(HEADER_SIZE, 0);
        if (header.getInt(0) != MAGIC || header.getInt(4) != 1 || header.getInt(8) != HEADER_SIZE
                || header.getInt(12) != regionX || header.getInt(16) != regionZ
                || header.getInt(20) != 0 || header.getLong(40) != 0 || header.getLong(48) != 0 || header.getInt(56) != 0
                || header.getInt(60) != crc(header.array(), 0, 60)) {
            throw corrupt("Invalid/unsupported header, coordinates or checksum");
        }
    }

    private void restoreIndex() throws IOException {
        long size = channel.size();
        byte[] scratch = new byte[64 * 1024];
        while (end < size) {
            if (size - end < RECORD_HEADER_SIZE) { recoverTail(size); break; }
            ByteBuffer h = readBuffer(RECORD_HEADER_SIZE, end);
            validateRecordHeader(h);
            int length = h.getInt(24);
            long total = RECORD_HEADER_SIZE + (long) length + COMMIT_SIZE;
            if (size - end < total) { recoverTail(size); break; }
            ByteBuffer commit = readBuffer(COMMIT_SIZE, end + RECORD_HEADER_SIZE + length);
            CRC32C checksum = new CRC32C();
            checksum.update(h.array());
            long offset = end + RECORD_HEADER_SIZE;
            int remaining = length;
            while (remaining > 0) {
                int count = Math.min(remaining, scratch.length);
                readFully(ByteBuffer.wrap(scratch, 0, count), offset);
                checksum.update(scratch, 0, count);
                remaining -= count;
                offset += count;
            }
            if (commit.getInt(0) != COMMIT_MAGIC || commit.getInt(4) != total || commit.getLong(8) != h.getLong(16)
                    || commit.getInt(16) != (int) checksum.getValue()
                    || commit.getInt(20) != crc(commit.array(), 0, 20)) {
                throw corrupt("Damaged committed record at byte " + end);
            }
            int slot = slot(h.getInt(8), h.getInt(12));
            index[slot] = h.get(6) == 2 ? null : entry(h, end);
            generation = h.getLong(16);
            committedRecords++;
            end += total;
        }
    }

    private void validateRecordHeader(ByteBuffer h) throws IOException {
        int type = Byte.toUnsignedInt(h.get(6));
        int codec = Byte.toUnsignedInt(h.get(7));
        int compressed = h.getInt(24);
        int raw = h.getInt(28);
        if (h.getInt(0) != RECORD_MAGIC || Short.toUnsignedInt(h.getShort(4)) != RECORD_HEADER_SIZE
                || (type != 1 && type != 2) || h.getLong(16) != generation + 1 || generation == Long.MAX_VALUE
                || (h.getInt(8) >> 5) != regionX || (h.getInt(12) >> 5) != regionZ
                || compressed < 0 || compressed > options.maxCompressedBytes() || raw < 0 || raw > options.maxRawBytes()
                || h.getInt(36) != 0 || h.getInt(40) != 0 || h.getInt(44) != crc(h.array(), 0, 44)) {
            throw corrupt("Invalid record header at byte " + end);
        }
        if (type == 2) {
            if (codec != 0 || compressed != 0 || raw != 0 || h.getInt(32) != 0) throw corrupt("Invalid tombstone");
        } else {
            if (compressed == 0) throw corrupt("Empty compressed record");
            codecs.require(codec);
        }
    }

    private void recoverTail(long size) throws IOException {
        Path saved = path.resolveSibling(path.getFileName() + ".uncommitted-" + UUID.randomUUID());
        try (FileChannel output = FileChannel.open(saved, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            long position = end;
            ByteBuffer bytes = buffer(64 * 1024);
            while (position < size) {
                bytes.clear().limit((int) Math.min(bytes.capacity(), size - position));
                int count = channel.read(bytes, position);
                if (count <= 0) throw new EOFException("Cannot preserve incomplete append");
                bytes.flip();
                while (bytes.hasRemaining()) output.write(bytes);
                position += count;
            }
            output.force(true);
        }
        channel.truncate(end);
        channel.force(true);
        recoveredTail = saved;
    }

    public synchronized Path recoveredTail() { return recoveredTail; }

    private static Entry entry(ByteBuffer h, long offset) {
        return new Entry(offset, h.getInt(24), h.getInt(28), h.getInt(32), Byte.toUnsignedInt(h.get(7)));
    }

    public synchronized boolean contains(int chunkX, int chunkZ) throws IOException {
        checkOpen();
        return index[checkedSlot(chunkX, chunkZ)] != null;
    }

    synchronized RegionStatistics statistics() throws IOException {
        checkOpen();
        long fileBytes = channel.size();
        long liveRecordBytes = 0;
        int liveChunks = 0;
        for (Entry entry : index) {
            if (entry != null) {
                liveChunks++;
                liveRecordBytes += RECORD_HEADER_SIZE + (long) entry.length() + COMMIT_SIZE;
            }
        }
        long reclaimableBytes = Math.max(0, fileBytes - HEADER_SIZE - liveRecordBytes);
        double obsoleteRatio = fileBytes == 0 ? 0 : (double) reclaimableBytes / fileBytes;
        boolean recommended = fileBytes >= RegionStatistics.MINIMUM_FILE_BYTES
                && reclaimableBytes >= RegionStatistics.MINIMUM_RECLAIMABLE_BYTES
                && obsoleteRatio >= RegionStatistics.MINIMUM_OBSOLETE_RATIO;
        return new RegionStatistics(fileBytes, committedRecords, liveChunks, liveRecordBytes,
                reclaimableBytes, obsoleteRatio, generation, recommended);
    }

    public synchronized byte[] read(int chunkX, int chunkZ) throws IOException {
        checkOpen();
        Entry entry = index[checkedSlot(chunkX, chunkZ)];
        if (entry == null) return null;
        ByteBuffer h = readBuffer(RECORD_HEADER_SIZE, entry.offset());
        if (h.getInt(44) != crc(h.array(), 0, 44) || !entry.equals(entry(h, entry.offset()))
                || h.getInt(8) != chunkX || h.getInt(12) != chunkZ || h.get(6) != 1) {
            throw corrupt("Record changed after opening at byte " + entry.offset());
        }
        byte[] compressed = readBuffer(entry.length(), entry.offset() + RECORD_HEADER_SIZE).array();
        ByteBuffer commit = readBuffer(COMMIT_SIZE, entry.offset() + RECORD_HEADER_SIZE + entry.length());
        CRC32C recordCrc = new CRC32C();
        recordCrc.update(h.array());
        recordCrc.update(compressed);
        if (commit.getInt(0) != COMMIT_MAGIC || commit.getInt(4) != RECORD_HEADER_SIZE + entry.length() + COMMIT_SIZE
                || commit.getLong(8) != h.getLong(16) || commit.getInt(16) != (int) recordCrc.getValue()
                || commit.getInt(20) != crc(commit.array(), 0, 20)) throw corrupt("Record checksum mismatch");
        byte[] raw;
        try { raw = codecs.require(entry.codec()).decompress(compressed, entry.rawLength()); }
        catch (IOException e) { throw new CorruptRegionException("Cannot decompress " + path + " at " + entry.offset(), e); }
        if (raw.length != entry.rawLength() || crc(raw, 0, raw.length) != entry.rawCrc()) throw corrupt("Payload checksum/length mismatch");
        return raw;
    }

    public synchronized void write(int chunkX, int chunkZ, byte[] payload) throws IOException {
        checkOpen();
        int slot = checkedSlot(chunkX, chunkZ);
        if (payload == null) { append(chunkX, chunkZ, slot, 2, new byte[0], 0, 0); return; }
        if (payload.length > options.maxRawBytes()) throw new IOException("Payload exceeds raw byte limit");
        byte[] compressed = codecs.require(1).compress(payload);
        if (compressed.length > options.maxCompressedBytes()) throw new IOException("Payload exceeds compressed byte limit");
        append(chunkX, chunkZ, slot, 1, compressed, payload.length, crc(payload, 0, payload.length));
    }

    public synchronized void delete(int chunkX, int chunkZ) throws IOException { write(chunkX, chunkZ, null); }

    private void append(int x, int z, int slot, int type, byte[] compressed, int rawLength, int rawCrc) throws IOException {
        if (generation == Long.MAX_VALUE) throw new IOException("Generation exhausted");
        ByteBuffer h = buffer(RECORD_HEADER_SIZE);
        h.putInt(RECORD_MAGIC).putShort((short) RECORD_HEADER_SIZE).put((byte) type).put((byte) (type == 1 ? 1 : 0));
        h.putInt(x).putInt(z).putLong(generation + 1).putInt(compressed.length).putInt(rawLength).putInt(rawCrc);
        h.position(44);
        h.putInt(crc(h.array(), 0, 44));
        CRC32C recordCrc = new CRC32C();
        recordCrc.update(h.array());
        recordCrc.update(compressed);
        int total = RECORD_HEADER_SIZE + compressed.length + COMMIT_SIZE;
        ByteBuffer commit = buffer(COMMIT_SIZE);
        commit.putInt(COMMIT_MAGIC).putInt(total).putLong(generation + 1).putInt((int) recordCrc.getValue());
        commit.putInt(crc(commit.array(), 0, 20)).flip();
        try {
            dirty = true;
            h.flip();
            writeFully(h, end);
            writeFully(ByteBuffer.wrap(compressed), end + RECORD_HEADER_SIZE);
            if (options.sync()) channel.force(false);
            writeFully(commit, end + RECORD_HEADER_SIZE + compressed.length);
            if (options.sync()) {
                channel.force(true);
                dirty = false;
            }
            index[slot] = type == 2 ? null : entry(h, end);
            generation++;
            committedRecords++;
            end += total;
        } catch (IOException e) {
            writeFailure = e;
            throw e;
        }
    }

    public synchronized void flush() throws IOException {
        checkOpen();
        if (!dirty) return;
        try { channel.force(true); }
        catch (IOException e) { writeFailure = e; throw e; }
        dirty = false;
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        IOException failure = writeFailure;
        if (dirty || writeFailure != null) {
            try {
                channel.force(true);
                dirty = false;
            } catch (IOException e) { failure = collect(failure, e); }
        }
        try { lock.release(); } catch (IOException e) { failure = collect(failure, e); }
        try { channel.close(); } catch (IOException e) { failure = collect(failure, e); }
        if (failure != null) throw failure;
    }

    static IOException collect(IOException first, IOException next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    private int checkedSlot(int x, int z) {
        if ((x >> 5) != regionX || (z >> 5) != regionZ) throw new IllegalArgumentException("Chunk outside region");
        return slot(x, z);
    }
    private static int slot(int x, int z) { return (x & 31) + (z & 31) * 32; }
    private void checkOpen() throws IOException {
        if (closed) throw new IOException("Region closed: " + path);
        if (writeFailure != null) throw new IOException("Region poisoned by an earlier write failure: " + path, writeFailure);
    }
    private CorruptRegionException corrupt(String message) { return new CorruptRegionException(message + ": " + path); }
    private static int crc(byte[] data, int start, int length) {
        CRC32C crc = new CRC32C();
        crc.update(data, start, length);
        return (int) crc.getValue();
    }
    private ByteBuffer readBuffer(int length, long offset) throws IOException {
        ByteBuffer bytes = buffer(length);
        readFully(bytes, offset);
        bytes.flip();
        return bytes;
    }
    private void readFully(ByteBuffer bytes, long offset) throws IOException {
        while (bytes.hasRemaining()) {
            int count = channel.read(bytes, offset);
            if (count <= 0) throw corrupt("Unexpected EOF at byte " + offset);
            offset += count;
        }
    }
    private void writeFully(ByteBuffer bytes, long offset) throws IOException {
        while (bytes.hasRemaining()) {
            int count = channel.write(bytes, offset);
            if (count <= 0) throw new IOException("Write made no progress: " + path);
            offset += count;
        }
    }
}
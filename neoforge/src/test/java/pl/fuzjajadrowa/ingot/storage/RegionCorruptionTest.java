package pl.fuzjajadrowa.ingot.storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import pl.fuzjajadrowa.ingot.compression.CompressionRegistry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.zip.CRC32C;

import static org.junit.Assert.*;

public class RegionCorruptionTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private static final StorageOptions OPTIONS = new StorageOptions(4096, 4096, 4, false);
    private static final int RECORD = IngotRegionFile.HEADER_SIZE;

    @Test public void rejectsInvalidRegionHeadersAndShortHeader() throws Exception {
        assertHeaderCorrupt("magic", bytes -> bytes[0] ^= 1);
        assertHeaderCorrupt("version", bytes -> ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(4, 2));
        assertHeaderCorrupt("checksum", bytes -> bytes[60] ^= 0x40);
        Path shortFile = temporary.newFolder("short-header").toPath().resolve("r.0.0.ir");
        Files.write(shortFile, new byte[IngotRegionFile.HEADER_SIZE - 1]);
        assertRejected(shortFile);
    }

    @Test public void rejectsInvalidCommittedRecordFieldsAndChecksums() throws Exception {
        assertRecordCorrupt("negative-compressed", bytes -> putInt(bytes, RECORD + 24, -1), true);
        assertRecordCorrupt("oversized-compressed", bytes -> putInt(bytes, RECORD + 24, 4097), true);
        assertRecordCorrupt("negative-raw", bytes -> putInt(bytes, RECORD + 28, -1), true);
        assertRecordCorrupt("oversized-raw", bytes -> putInt(bytes, RECORD + 28, 4097), true);
        assertRecordCorrupt("wrong-coordinate", bytes -> putInt(bytes, RECORD + 8, 32), true);
        assertRecordCorrupt("wrong-generation", bytes -> putLong(bytes, RECORD + 16, 2), true);
        assertRecordCorrupt("unknown-codec", bytes -> bytes[RECORD + 7] = 99, true);
        assertRecordCorrupt("payload", bytes -> bytes[RECORD + IngotRegionFile.RECORD_HEADER_SIZE] ^= 0x20, false);
        assertRecordCorrupt("commit-magic", bytes -> putInt(bytes, commitOffset(bytes), 0), false);
        assertRecordCorrupt("commit-length", bytes -> putInt(bytes, commitOffset(bytes) + 4, 1), false);
        assertRecordCorrupt("commit-checksum", bytes -> bytes[commitOffset(bytes) + 20] ^= 1, false);
        assertRecordCorrupt("payload-checksum", bytes -> {
            putInt(bytes, RECORD + 32, 0x12345678);
            updateRecordAndCommitChecksums(bytes);
        }, false);
    }

    @Test public void truncatedCommittedRecordIsRecoveredButNotReadAsAChunk() throws Exception {
        byte[] valid = fixture();
        Path path = temporary.newFolder("truncated-record").toPath().resolve("r.0.0.ir");
        Files.write(path, java.util.Arrays.copyOf(valid, valid.length - 1));
        Path tail;
        try (IngotRegionFile region = open(path)) {
            assertNull(region.read(0, 0));
            tail = region.recoveredTail();
            assertNotNull(tail);
            assertEquals(IngotRegionFile.HEADER_SIZE, Files.size(path));
            assertEquals(valid.length - IngotRegionFile.HEADER_SIZE - 1L, Files.size(tail));
        }
        assertTrue(Files.exists(tail));
    }

    private void assertHeaderCorrupt(String name, Consumer<byte[]> mutate) throws Exception {
        byte[] bytes = fixture();
        mutate.accept(bytes);
        Path path = path(name);
        Files.write(path, bytes);
        assertRejected(path);
        assertEquals("Rejected file must be preserved", bytes.length, Files.size(path));
    }

    private void assertRecordCorrupt(String name, Consumer<byte[]> mutate, boolean recalcHeader) throws Exception {
        byte[] bytes = fixture();
        mutate.accept(bytes);
        if (recalcHeader) updateHeaderChecksum(bytes);
        Path path = path(name);
        Files.write(path, bytes);
        assertRejected(path);
        assertEquals("Committed corruption must not be silently truncated: " + name, bytes.length, Files.size(path));
    }

    private void assertRejected(Path path) throws Exception {
        try (IngotRegionFile region = open(path)) {
            region.read(0, 0);
            fail("Corrupt file must be rejected: " + path);
        }
        catch (IOException expected) { assertNotNull("Failure should identify the damaged file", expected.getMessage()); }
    }

    private Path path(String name) throws Exception { return temporary.newFolder(name).toPath().resolve("r.0.0.ir"); }
    private IngotRegionFile open(Path path) throws IOException {
        return new IngotRegionFile(path, 0, 0, OPTIONS, CompressionRegistry.defaults());
    }
    private byte[] fixture() throws Exception {
        Path path = Files.createTempDirectory(temporary.getRoot().toPath(), "fixture-").resolve("r.0.0.ir");
        try (IngotRegionFile region = open(path)) { region.write(0, 0, new byte[] {4, 5, 6, 7}); }
        return Files.readAllBytes(path);
    }
    private static void putInt(byte[] bytes, int offset, int value) { ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putInt(offset, value); }
    private static void putLong(byte[] bytes, int offset, long value) { ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).putLong(offset, value); }
    private static int commitOffset(byte[] bytes) { return bytes.length - IngotRegionFile.COMMIT_SIZE; }
    private static void updateHeaderChecksum(byte[] bytes) {
        CRC32C crc = new CRC32C(); crc.update(bytes, RECORD, 44); putInt(bytes, RECORD + 44, (int) crc.getValue());
    }
    private static void updateRecordAndCommitChecksums(byte[] bytes) {
        updateHeaderChecksum(bytes);
        int compressedLength = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).getInt(RECORD + 24);
        int commit = RECORD + IngotRegionFile.RECORD_HEADER_SIZE + compressedLength;
        CRC32C recordCrc = new CRC32C();
        recordCrc.update(bytes, RECORD, IngotRegionFile.RECORD_HEADER_SIZE + compressedLength);
        putInt(bytes, commit + 16, (int) recordCrc.getValue());
        CRC32C commitCrc = new CRC32C(); commitCrc.update(bytes, commit, 20); putInt(bytes, commit + 20, (int) commitCrc.getValue());
    }
}
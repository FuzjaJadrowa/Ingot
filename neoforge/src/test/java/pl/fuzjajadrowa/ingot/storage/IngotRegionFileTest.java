package pl.fuzjajadrowa.ingot.storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import pl.fuzjajadrowa.ingot.compression.CompressionRegistry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.Random;
import java.util.zip.CRC32C;

import static org.junit.Assert.*;

public class IngotRegionFileTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private final StorageOptions options = new StorageOptions(1024 * 1024, 1024 * 1024, 4, false);
    private final CompressionRegistry codecs = CompressionRegistry.defaults();

    private Path regionPath(String name) throws IOException {
        return temporary.getRoot().toPath().resolve(name);
    }

    private IngotRegionFile open(Path path, int regionX, int regionZ) throws IOException {
        return new IngotRegionFile(path, regionX, regionZ, options, codecs);
    }

    @Test public void createsAndValidatesEmptyRegion() throws Exception {
        Path path = regionPath("r.0.0.ir");
        try (IngotRegionFile region = open(path, 0, 0)) {
            assertEquals(IngotRegionFile.HEADER_SIZE, Files.size(path));
            assertFalse(region.contains(0, 0));
            assertEquals(0, region.statistics().committedRecords());
        }
        try (IngotRegionFile ignored = open(path, 0, 0)) { }

        byte[] header = Files.readAllBytes(path);
        header[0] ^= 1;
        Files.write(path, header);
        try {
            open(path, 0, 0).close();
            fail("Expected invalid header to be rejected");
        } catch (CorruptRegionException expected) { }
    }

    @Test public void roundTripsOverwritesAndRebuildsIndex() throws Exception {
        Path path = regionPath("r.0.0.ir");
        byte[] first = "first payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] latest = new byte[80_000];
        new Random(1941).nextBytes(latest);
        try (IngotRegionFile region = open(path, 0, 0)) {
            region.write(7, 9, first);
            region.write(7, 9, latest);
            assertArrayEquals(latest, region.read(7, 9));
            RegionStatistics stats = region.statistics();
            assertEquals(2, stats.committedRecords());
            assertEquals(1, stats.liveChunks());
            assertTrue(stats.reclaimableBytes() > 0);
        }
        try (IngotRegionFile reopened = open(path, 0, 0)) {
            assertArrayEquals(latest, reopened.read(7, 9));
            assertEquals(2, reopened.statistics().committedRecords());
        }
    }

    @Test public void readsRegionBuiltFromVersion010FormatWithoutRewritingIt() throws Exception {
        Path path = regionPath("r.0.0.ir");
        byte[] payload = "fixture written with the 0.1.0 region-v1 layout".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] fixture = version010Fixture(3, 5, payload);
        Files.write(path, fixture);

        try (IngotRegionFile region = open(path, 0, 0)) {
            assertArrayEquals(payload, region.read(3, 5));
            assertEquals(1, region.statistics().generation());
            assertEquals(1, region.statistics().committedRecords());
        }
        assertArrayEquals("Opening an old region must not rewrite it", fixture, Files.readAllBytes(path));
    }

    /** Encodes the v1 fields as implemented by the 0.1.0 source, independently of IngotRegionFile.write(). */
    private byte[] version010Fixture(int chunkX, int chunkZ, byte[] payload) throws IOException {
        byte[] compressed = codecs.require(1).compress(payload);
        ByteBuffer header = ByteBuffer.allocate(64).order(ByteOrder.BIG_ENDIAN);
        UUID id = UUID.fromString("4dc5b041-742c-45eb-9033-306932c5c5a9");
        header.putInt(0x494e4754).putInt(1).putInt(64).putInt(0).putInt(0).putInt(0);
        header.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        header.position(60);
        header.putInt(testCrc(header.array(), 0, 60));

        ByteBuffer record = ByteBuffer.allocate(48).order(ByteOrder.BIG_ENDIAN);
        record.putInt(0x49524344).putShort((short) 48).put((byte) 1).put((byte) 1);
        record.putInt(chunkX).putInt(chunkZ).putLong(1).putInt(compressed.length).putInt(payload.length);
        record.putInt(testCrc(payload, 0, payload.length));
        record.position(44);
        record.putInt(testCrc(record.array(), 0, 44));

        int totalRecordBytes = 48 + compressed.length + 24;
        CRC32C recordCrc = new CRC32C();
        recordCrc.update(record.array());
        recordCrc.update(compressed);
        ByteBuffer commit = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN);
        commit.putInt(0x49434d54).putInt(totalRecordBytes).putLong(1).putInt((int) recordCrc.getValue());
        commit.putInt(testCrc(commit.array(), 0, 20));

        ByteBuffer file = ByteBuffer.allocate(64 + totalRecordBytes);
        file.put(header.array()).put(record.array()).put(compressed).put(commit.array());
        return file.array();
    }

    private static int testCrc(byte[] bytes, int offset, int length) {
        CRC32C crc = new CRC32C();
        crc.update(bytes, offset, length);
        return (int) crc.getValue();
    }

    @Test public void tombstonesStayDeletedAcrossReopen() throws Exception {
        Path path = regionPath("r.0.0.ir");
        try (IngotRegionFile region = open(path, 0, 0)) {
            region.write(1, 2, new byte[]{1, 2, 3});
            region.delete(1, 2);
            assertFalse(region.contains(1, 2));
            assertNull(region.read(1, 2));
        }
        try (IngotRegionFile reopened = open(path, 0, 0)) {
            assertFalse(reopened.contains(1, 2));
            assertEquals(2, reopened.statistics().committedRecords());
        }
    }

    @Test public void supportsNegativeChunkAndRegionCoordinates() throws Exception {
        Path path = regionPath("r.-1.-2.ir");
        try (IngotRegionFile region = open(path, -1, -2)) {
            region.write(-1, -33, new byte[]{9, 8, 7});
            assertArrayEquals(new byte[]{9, 8, 7}, region.read(-1, -33));
            try {
                region.read(0, -33);
                fail("Chunk in another region must be rejected");
            } catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void enforcesPayloadLimitAndRoundTripsZstandard() throws Exception {
        Path path = regionPath("r.0.0.ir");
        StorageOptions limited = new StorageOptions(64, 128, 2, false);
        try (IngotRegionFile region = new IngotRegionFile(path, 0, 0, limited, codecs)) {
            byte[] payload = new byte[64];
            new Random(42).nextBytes(payload);
            region.write(0, 0, payload);
            assertArrayEquals(payload, region.read(0, 0));
            try {
                region.write(1, 0, new byte[65]);
                fail("Oversized raw payload must be rejected");
            } catch (IOException expected) { }
        }
    }

    @Test public void reportsCorruptCommittedPayload() throws Exception {
        Path path = regionPath("r.0.0.ir");
        try (IngotRegionFile region = open(path, 0, 0)) { region.write(0, 0, new byte[200]); }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            ByteBuffer byteToCorrupt = ByteBuffer.wrap(new byte[]{0x55});
            channel.write(byteToCorrupt, IngotRegionFile.HEADER_SIZE + IngotRegionFile.RECORD_HEADER_SIZE);
        }
        try {
            open(path, 0, 0).close();
            fail("Committed record checksum must be verified");
        } catch (CorruptRegionException expected) { }
    }

    @Test public void rejectsInvalidRecordLength() throws Exception {
        Path path = regionPath("r.0.0.ir");
        try (IngotRegionFile region = open(path, 0, 0)) { region.write(0, 0, new byte[]{1}); }
        byte[] record = Files.readAllBytes(path);
        ByteBuffer header = ByteBuffer.wrap(record).order(ByteOrder.BIG_ENDIAN);
        int offset = IngotRegionFile.HEADER_SIZE;
        header.putInt(offset + 24, options.maxCompressedBytes() + 1);
        CRC32C crc = new CRC32C();
        crc.update(record, offset, 44);
        header.putInt(offset + 44, (int) crc.getValue());
        Files.write(path, record);
        try {
            open(path, 0, 0).close();
            fail("Invalid length must be rejected");
        } catch (CorruptRegionException expected) { }
    }

    @Test public void preservesAndTruncatesUncommittedTail() throws Exception {
        Path path = regionPath("r.0.0.ir");
        try (IngotRegionFile region = open(path, 0, 0)) { region.write(2, 3, new byte[]{4, 5, 6}); }
        long committedSize = Files.size(path);
        Files.write(path, new byte[]{11, 12, 13, 14}, StandardOpenOption.APPEND);
        try (IngotRegionFile reopened = open(path, 0, 0)) {
            assertArrayEquals(new byte[]{4, 5, 6}, reopened.read(2, 3));
            assertNotNull(reopened.recoveredTail());
            assertTrue(Files.exists(reopened.recoveredTail()));
            assertEquals(committedSize, Files.size(path));
        }
    }

    @Test public void preventsSimultaneousOpenAndReleasesLockOnClose() throws Exception {
        Path path = regionPath("r.0.0.ir");
        IngotRegionFile first = open(path, 0, 0);
        try {
            try {
                open(path, 0, 0).close();
                fail("A region must not be opened twice");
            } catch (IOException expected) { }
        } finally { first.close(); }
        try (IngotRegionFile reopened = open(path, 0, 0)) { }
    }

    @Test public void compactionPreservesLivePayloadsAndDropsDeletedRecords() throws Exception {
        Path path = regionPath("r.0.0.ir");
        byte[] latest = new byte[32_000];
        new Random(77).nextBytes(latest);
        long oldSize;
        try (IngotRegionFile region = open(path, 0, 0)) {
            region.write(1, 1, new byte[32_000]);
            region.write(1, 1, latest);
            region.write(2, 2, new byte[]{4, 5});
            region.delete(2, 2);
            oldSize = Files.size(path);
            Path candidate = regionPath("r.0.0.ir.candidate");
            region.compactTo(candidate);
            assertFalse(Files.exists(candidate));
            assertArrayEquals(latest, region.read(1, 1));
            assertNull(region.read(2, 2));
            RegionStatistics stats = region.statistics();
            assertEquals(1, stats.committedRecords());
            assertEquals(1, stats.liveChunks());
            assertEquals(1, stats.generation());
        }
        assertTrue(Files.size(path) < oldSize);
        try (IngotRegionFile reopened = open(path, 0, 0)) { assertArrayEquals(latest, reopened.read(1, 1)); }
    }

    @Test public void compactionKeepsAnAllDeletedRegionEmpty() throws Exception {
        Path path = regionPath("r.0.0.ir");
        try (IngotRegionFile region = open(path, 0, 0)) {
            region.write(1, 1, new byte[]{1});
            region.delete(1, 1);
            region.compactTo(regionPath("r.0.0.ir.empty-candidate"));
            assertNull(region.read(1, 1));
            assertEquals(0, region.statistics().liveChunks());
            assertEquals(0, region.statistics().committedRecords());
            assertEquals(IngotRegionFile.HEADER_SIZE, Files.size(path));
        }
    }

    @Test public void compactionPolicyRequiresAllConservativeThresholds() throws Exception {
        Path path = regionPath("r.0.0.ir");
        StorageOptions largeOptions = new StorageOptions(2 * 1024 * 1024, 2 * 1024 * 1024, 2, false);
        byte[] payload = new byte[1_100_000];
        new Random(123).nextBytes(payload);
        try (IngotRegionFile region = new IngotRegionFile(path, 0, 0, largeOptions, codecs)) {
            region.write(0, 0, payload);
            assertFalse(region.statistics().compactionRecommended());
            region.write(0, 0, payload);
            assertTrue(region.statistics().compactionRecommended());
        }
    }

    @Test public void concurrentWriteAndCompactionDoNotLoseCommittedPayload() throws Exception {
        Path directory = temporary.getRoot().toPath();
        IngotStorage storage = new IngotStorage(directory, options, codecs, ignored -> { });
        byte[] original = new byte[64_000];
        byte[] update = new byte[64_000];
        new Random(4).nextBytes(original);
        new Random(5).nextBytes(update);
        storage.write(4, 4, original);
        storage.write(4, 4, new byte[]{1});

        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var write = executor.submit(() -> { start.await(); storage.write(4, 4, update); return null; });
            var compact = executor.submit(() -> { start.await(); storage.compactRegion(4, 4); return null; });
            start.countDown();
            write.get();
            compact.get();
            assertArrayEquals(update, storage.read(4, 4));
        } finally {
            executor.shutdownNow();
            storage.close();
        }
        try (IngotRegionFile reopened = open(directory.resolve("r.0.0.ir"), 0, 0)) {
            assertArrayEquals(update, reopened.read(4, 4));
        }
    }

    @Test public void failedCandidateCreationLeavesOriginalReadable() throws Exception {
        Path path = regionPath("r.0.0.ir");
        try (IngotRegionFile region = open(path, 0, 0)) {
            byte[] payload = {3, 1, 4};
            region.write(0, 0, payload);
            Path occupied = regionPath("occupied.tmp");
            Files.write(occupied, new byte[]{1});
            try {
                region.compactTo(occupied);
                fail("Existing candidate must not be overwritten");
            } catch (IOException expected) { }
            assertArrayEquals(payload, region.read(0, 0));
        }
    }

    @Test public void failedAtomicPublicationReopensSourceAndPreservesVerifiedCandidate() throws Exception {
        Path path = regionPath("r.0.0.ir");
        Path candidate = regionPath("r.0.0.ir.compacting-test");
        byte[] latest = {9, 8, 7, 6};
        try (IngotRegionFile region = open(path, 0, 0)) {
            region.write(0, 0, new byte[]{1, 2, 3});
            region.write(0, 0, latest);
            try {
                region.compactTo(candidate, (source, target) -> {
                    throw new IOException("injected atomic publication failure");
                });
                fail("Injected publication failure must be reported");
            } catch (IOException expected) {
                assertEquals("injected atomic publication failure", expected.getMessage());
            }

            assertTrue("Verified candidate must remain available for inspection", Files.exists(candidate));
            assertFalse("Candidate lock must be released before publication", Files.exists(
                    candidate.resolveSibling(candidate.getFileName() + ".lock")));
            assertArrayEquals(latest, region.read(0, 0));
        }

        try (IngotRegionFile reopened = open(path, 0, 0)) {
            assertArrayEquals(latest, reopened.read(0, 0));
        }
    }
}
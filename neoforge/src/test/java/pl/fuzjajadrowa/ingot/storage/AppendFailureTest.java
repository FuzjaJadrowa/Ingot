package pl.fuzjajadrowa.ingot.storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import pl.fuzjajadrowa.ingot.compression.CompressionRegistry;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class AppendFailureTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private static final byte[] OLD = {11, 12};
    private static final byte[] NEW = {21, 22, 23};

    @Test public void injectedFailuresBeforeCommitRecoverPreviousOverwrite() throws Exception {
        for (RegionFaultInjector.Point point : new RegionFaultInjector.Point[] {
                RegionFaultInjector.Point.AFTER_HEADER, RegionFaultInjector.Point.AFTER_PAYLOAD,
                RegionFaultInjector.Point.AFTER_DATA_FORCE }) {
            Path path = writeOld("before-" + point, true);
            try {
                try (IngotRegionFile region = open(path, true, point)) { region.write(0, 0, NEW); }
                fail("Expected injection at " + point);
            } catch (IOException expected) {
                assertTrue(expected.toString() + " " + java.util.Arrays.toString(expected.getSuppressed()),
                        throwableText(expected).contains(point.name()));
            }
            try (IngotRegionFile reopened = openClean(path, true)) {
                assertArrayEquals(OLD, reopened.read(0, 0));
                assertNull(reopened.read(1, 0));
            }
        }
    }

    @Test public void committedFailureIsRecoveredAsNewRecord() throws Exception {
        for (RegionFaultInjector.Point point : new RegionFaultInjector.Point[] {
                RegionFaultInjector.Point.AFTER_COMMIT, RegionFaultInjector.Point.AFTER_COMMIT_FORCE }) {
            Path path = writeOld("after-" + point, true);
            try {
                try (IngotRegionFile region = open(path, true, point)) { region.write(0, 0, NEW); }
                fail("Expected injection at " + point);
            } catch (IOException expected) {
                assertTrue(throwableText(expected).contains(point.name()));
            }
            try (IngotRegionFile reopened = openClean(path, true)) {
                assertArrayEquals(NEW, reopened.read(0, 0));
                assertEquals(2, reopened.statistics().committedRecords());
            }
        }
    }

    @Test public void disabledSyncDoesNotInvokeForceCheckpoints() throws Exception {
        Path path = writeOld("sync-disabled", false);
        RegionFaultInjector onlyAtForce = point -> {
            if (point == RegionFaultInjector.Point.AFTER_DATA_FORCE
                    || point == RegionFaultInjector.Point.AFTER_COMMIT_FORCE) throw new IOException("unexpected force checkpoint");
        };
        try (IngotRegionFile region = new IngotRegionFile(path, 0, 0, options(false),
                CompressionRegistry.defaults(), onlyAtForce)) {
            region.write(0, 0, NEW);
            assertArrayEquals(NEW, region.read(0, 0));
        }
    }

    private Path writeOld(String name, boolean sync) throws Exception {
        Path path = temporary.newFolder(name).toPath().resolve("r.0.0.ir");
        try (IngotRegionFile region = new IngotRegionFile(path, 0, 0, options(sync),
                CompressionRegistry.defaults())) { region.write(0, 0, OLD); }
        return path;
    }
    private IngotRegionFile open(Path path, boolean sync, RegionFaultInjector.Point failAt) throws IOException {
        return new IngotRegionFile(path, 0, 0, options(sync), CompressionRegistry.defaults(), point -> {
            if (point == failAt) throw new IOException("injected " + point.name());
        });
    }
    private IngotRegionFile openClean(Path path, boolean sync) throws IOException {
        return new IngotRegionFile(path, 0, 0, options(sync), CompressionRegistry.defaults());
    }
    private String throwableText(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) text.append(current).append(' ');
        for (Throwable suppressed : failure.getSuppressed()) text.append(throwableText(suppressed));
        return text.toString();
    }
    private StorageOptions options(boolean sync) { return new StorageOptions(4096, 4096, 4, sync); }
}
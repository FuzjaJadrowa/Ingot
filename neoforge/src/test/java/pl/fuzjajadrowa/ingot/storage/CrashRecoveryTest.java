package pl.fuzjajadrowa.ingot.storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import pl.fuzjajadrowa.ingot.compression.CompressionRegistry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class CrashRecoveryTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private static final StorageOptions OPTIONS = new StorageOptions(1024 * 1024, 1024 * 1024, 4, true);
    private static final byte[] OLD = "OLD committed payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] NEW = "NEW committed payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private Path prepare(String name) throws Exception {
        Path path = temporary.newFolder(name).toPath().resolve("r.0.0.ir");
        try (IngotRegionFile region = open(path, RegionFaultInjector.NONE)) { region.write(0, 0, OLD); }
        return path;
    }

    private IngotRegionFile open(Path path, RegionFaultInjector injector) throws IOException {
        return new IngotRegionFile(path, 0, 0, OPTIONS, CompressionRegistry.defaults(), injector);
    }

    private void haltChild(String action, RegionFaultInjector.Point point, Path path) throws Exception {
        String classpath = System.getProperty("ingot.test.classpath");
        assertNotNull("Gradle must supply the test runtime classpath", classpath);
        Path output = path.resolveSibling(path.getFileName() + ".child-output");
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java").toString();
        Process child = new ProcessBuilder(java, "-cp", classpath, CrashProcess.class.getName(), action,
                point.name(), path.toString(), "true").redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue("Child JVM timed out at " + point, child.waitFor(20, TimeUnit.SECONDS));
            assertEquals("Crash child did not halt at expected checkpoint; output: " + Files.readString(output),
                    73, child.exitValue());
        } finally {
            if (child.isAlive()) child.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            Files.deleteIfExists(output);
        }
    }

    @Test public void processCrashDuringAppendKeepsOrPublishesOnlyCommittedOverwrite() throws Exception {
        for (RegionFaultInjector.Point point : new RegionFaultInjector.Point[] {
                RegionFaultInjector.Point.AFTER_HEADER, RegionFaultInjector.Point.AFTER_PAYLOAD,
                RegionFaultInjector.Point.AFTER_DATA_FORCE, RegionFaultInjector.Point.AFTER_COMMIT,
                RegionFaultInjector.Point.AFTER_COMMIT_FORCE }) {
            Path path = prepare("append-" + point);
            haltChild("write", point, path);
            try (IngotRegionFile reopened = open(path, RegionFaultInjector.NONE)) {
                byte[] expected = point == RegionFaultInjector.Point.AFTER_COMMIT
                        || point == RegionFaultInjector.Point.AFTER_COMMIT_FORCE ? NEW : OLD;
                assertArrayEquals("Unexpected payload after " + point, expected, reopened.read(0, 0));
                if (expected == OLD) assertNotNull("Incomplete tail must be preserved", reopened.recoveredTail());
            }
        }
    }

    @Test public void processCrashDuringCompactionNeverSelectsUnpublishedCandidate() throws Exception {
        for (RegionFaultInjector.Point point : new RegionFaultInjector.Point[] {
                RegionFaultInjector.Point.DURING_CANDIDATE, RegionFaultInjector.Point.AFTER_CANDIDATE,
                RegionFaultInjector.Point.AFTER_VALIDATION, RegionFaultInjector.Point.AFTER_SOURCE_CLOSE,
                RegionFaultInjector.Point.AFTER_PUBLISH }) {
            Path path = prepare("compact-" + point);
            try (IngotRegionFile region = open(path, RegionFaultInjector.NONE)) {
                region.write(1, 1, new byte[] {3});
                region.write(2, 2, new byte[] {4});
                region.delete(2, 2);
            }
            haltChild("compact", point, path);
            Path candidate = path.resolveSibling(path.getFileName() + ".compacting-child");
            if (point == RegionFaultInjector.Point.AFTER_PUBLISH) assertFalse(Files.exists(candidate));
            else assertTrue("Candidate should remain for inspection after crash", Files.exists(candidate));
            try (IngotRegionFile reopened = open(path, RegionFaultInjector.NONE)) {
                assertArrayEquals(OLD, reopened.read(0, 0));
                assertArrayEquals(new byte[] {3}, reopened.read(1, 1));
                assertNull("Deleted record must not return", reopened.read(2, 2));
            }
            if (point != RegionFaultInjector.Point.AFTER_PUBLISH) {
                try (IngotStorage storage = new IngotStorage(path.getParent(), OPTIONS,
                        CompressionRegistry.defaults(), ignored -> { })) {
                    assertArrayEquals(OLD, storage.read(0, 0));
                    assertArrayEquals(new byte[] {3}, storage.read(1, 1));
                    assertNull(storage.read(2, 2));
                }
                assertFalse("Validated active region makes stale candidate safe to discard", Files.exists(candidate));
            }
        }
    }
}
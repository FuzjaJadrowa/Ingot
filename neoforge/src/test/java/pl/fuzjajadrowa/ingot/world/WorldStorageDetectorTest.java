package pl.fuzjajadrowa.ingot.world;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.Assert.assertEquals;

public class WorldStorageDetectorTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private Path world(String name) throws Exception { return temporary.newFolder(name).toPath(); }
    private void file(Path root, String relative) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {1});
    }

    @Test public void identifiesEmptyAnvilMixedAndOrphanIngotWorlds() throws Exception {
        Path empty = world("empty");
        assertEquals(WorldStorageDetector.Format.EMPTY, WorldStorageDetector.inspect(empty).format());

        Path anvil = world("anvil");
        file(anvil, "DIM-1/region/r.-2.3.mca");
        assertEquals(WorldStorageDetector.Format.ANVIL, WorldStorageDetector.inspect(anvil).format());

        Path ingot = world("ingot");
        file(ingot, "dimensions/mod/example/poi/ingot/r.0.0.ir");
        new IngotWorldManifest(UUID.randomUUID()).write(ingot);
        assertEquals(WorldStorageDetector.Format.INGOT, WorldStorageDetector.inspect(ingot).format());

        file(ingot, "region/r.0.0.mca");
        assertEquals(WorldStorageDetector.Format.MIXED, WorldStorageDetector.inspect(ingot).format());

        Path orphan = world("orphan");
        file(orphan, "region/ingot/r.0.0.ir");
        assertEquals(WorldStorageDetector.Format.UNSAFE, WorldStorageDetector.inspect(orphan).format());
    }

    @Test public void detectsExternalChunksAndPendingConversion() throws Exception {
        Path anvil = world("external");
        file(anvil, "dimensions/mod/example/entities/c.-1.4.mcc");
        assertEquals(WorldStorageDetector.Format.ANVIL, WorldStorageDetector.inspect(anvil).format());

        Files.writeString(anvil.resolve("ingot-conversion.journal"), "phase=staging\n");
        assertEquals(WorldStorageDetector.Format.UNSAFE, WorldStorageDetector.inspect(anvil).format());
    }

    @Test public void invalidManifestIsUnsafe() throws Exception {
        Path root = world("bad-manifest");
        Files.writeString(root.resolve(IngotWorldManifest.FILE_NAME), "damaged");
        assertEquals(WorldStorageDetector.Format.UNSAFE, WorldStorageDetector.inspect(root).format());
    }
}
package pl.fuzjajadrowa.ingot.world;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.Assert.*;

public class IngotWorldManagerTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private Path world(String name) throws IOException { return temporary.newFolder(name).toPath(); }
    private void marker(Path folder, String name) throws IOException {
        Files.createDirectories(folder);
        Files.write(folder.resolve(name), new byte[] {1});
    }

    @Test public void emptyWorldActivatesAndCanBeReopenedAfterClose() throws Exception {
        Path root = world("empty");
        IngotWorldManager.Session first = IngotWorldManager.openWorld(root);
        assertNotNull(first);
        IngotWorldManager.closeWorld(first);
        IngotWorldManager.Session second = IngotWorldManager.openWorld(root);
        assertNotNull(second);
        IngotWorldManager.closeWorld(second);
    }

    @Test public void detectsAnvilInAllStorageTypesAndLeavesWorldUnmarked() throws Exception {
        for (String storageType : new String[] {"region", "entities", "poi"}) {
            Path root = world("anvil-" + storageType);
            marker(root.resolve(storageType), "r.0.0.mca");
            assertNull(IngotWorldManager.openWorld(root));
            assertFalse(Files.exists(root.resolve(IngotWorldManifest.FILE_NAME)));
        }
    }

    @Test public void ignoresBackupFilesAndIrOutsideStorageDirectories() throws Exception {
        Path root = world("unrelated");
        marker(root.resolve("backups/region"), "r.0.0.mca");
        marker(root.resolve("DIMBackup/region"), "r.1.0.mca");
        marker(root.resolve("misc"), "r.0.0.ir");
        IngotWorldManager.Session session = IngotWorldManager.openWorld(root);
        assertNotNull(session);
        IngotWorldManager.closeWorld(session);
    }

    @Test public void findsModdedDimensionStorageAndRejectsMixedBackends() throws Exception {
        Path root = world("modded");
        marker(root.resolve("dimensions/example/astral/poi"), "r.0.0.mca");
        assertNull(IngotWorldManager.openWorld(root));

        Path ingot = world("modded-ingot");
        marker(ingot.resolve("dimensions/example/astral/entities/ingot"), "r.-1.2.ir");
        new IngotWorldManifest(UUID.randomUUID()).write(ingot);
        IngotWorldManager.Session ingotSession = IngotWorldManager.openWorld(ingot);
        assertNotNull(ingotSession);
        IngotWorldManager.closeWorld(ingotSession);

        Path mixed = world("mixed");
        marker(mixed.resolve("DIM-1/entities"), "r.0.0.mca");
        marker(mixed.resolve("dimensions/example/astral/region/ingot"), "r.0.0.ir");
        new IngotWorldManifest(UUID.randomUUID()).write(mixed);
        try {
            IngotWorldManager.openWorld(mixed);
            fail("Mixed active backends must be rejected");
        } catch (IOException expected) { assertTrue(expected.getMessage().contains("mixed backends")); }
    }

    @Test public void refusesIngotDataWithoutManifestAndInterruptedActivation() throws Exception {
        Path root = world("orphan");
        marker(root.resolve("region/ingot"), "r.0.0.ir");
        try { IngotWorldManager.openWorld(root); fail("Orphan Ingot data must not fall back to Anvil"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("without its manifest")); }

        Path pending = world("pending");
        Files.writeString(pending.resolve(IngotWorldManifest.FILE_NAME + ".pending"), "incomplete");
        try { IngotWorldManager.openWorld(pending); fail("Pending activation must be preserved and refused"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Interrupted Ingot activation")); }
    }
}

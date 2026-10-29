package pl.fuzjajadrowa.ingot.world;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import pl.fuzjajadrowa.ingot.compression.CompressionRegistry;
import pl.fuzjajadrowa.ingot.storage.IngotStorage;
import pl.fuzjajadrowa.ingot.storage.StorageOptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.Assert.*;

public class IngotWorldManagerTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private Path world(String name) throws IOException {
        return temporary.newFolder(name).toPath();
    }

    private void marker(Path folder, String name) throws IOException {
        Files.createDirectories(folder);
        Files.write(folder.resolve(name), new byte[] {1});
    }

    @Test public void emptyWorldActivatesAndCanBeReopenedAfterClose() throws Exception {
        Path root = world("empty");
        IngotWorldManager.Session first = IngotWorldManager.openWorld(root);
        assertNotNull(first);
        assertEquals(first.root(), root.toRealPath());
        IngotWorldManager.closeWorld(first);

        IngotWorldManager.Session second = IngotWorldManager.openWorld(root);
        assertNotNull(second);
        IngotWorldManager.closeWorld(second);
    }

    @Test public void detectsAnvilInAllRootStorageTypesAndLeavesWorldUnmarked() throws Exception {
        int i = 0;
        for (String storageType : new String[] {"region", "entities", "poi"}) {
            Path root = world("anvil-" + i++);
            marker(root.resolve(storageType), "r.0.0.mca");
            assertNull(IngotWorldManager.openWorld(root));
            assertFalse(Files.exists(root.resolve(IngotWorldManifest.FILE_NAME)));
        }
    }

    @Test public void detectsAnvilInLegacyAndModdedDimensions() throws Exception {
        Path nether = world("legacy-nether");
        marker(nether.resolve("DIM-1/region"), "r.-1.2.mca");
        assertNull(IngotWorldManager.openWorld(nether));

        Path end = world("legacy-end");
        marker(end.resolve("DIM1/poi"), "r.0.0.mca");
        assertNull(IngotWorldManager.openWorld(end));

        Path modded = world("modded-dimension");
        marker(modded.resolve("dimensions/example/lol/valley/poi"), "r.0.0.mca");
        assertNull(IngotWorldManager.openWorld(modded));
    }

    @Test public void refusesMalformedArtifactsAndUnexpectedTypesAtActiveStoragePaths() throws Exception {
        Path malformedAnvil = world("malformed-anvil-name");
        marker(malformedAnvil.resolve("region"), "r.not-a-coordinate.mca");
        try {
            IngotWorldManager.openWorld(malformedAnvil);
            fail("Malformed region artifacts in a live store must not select a backend");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("ambiguous path"));
        }
        assertFalse(Files.exists(malformedAnvil.resolve(IngotWorldManifest.FILE_NAME)));

        Path malformedIngot = world("malformed-ingot-name");
        marker(malformedIngot.resolve("region/ingot"), "chunk-data.ir");
        try {
            IngotWorldManager.openWorld(malformedIngot);
            fail("Malformed Ingot artifacts must not trigger a new manifest");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("ambiguous path"));
        }
        assertFalse(Files.exists(malformedIngot.resolve(IngotWorldManifest.FILE_NAME)));

        Path wrongType = world("region-file-is-directory");
        Files.createDirectories(wrongType.resolve("region/r.0.0.mca"));
        try {
            IngotWorldManager.openWorld(wrongType);
            fail("A directory named like an Anvil region must be refused");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("ambiguous path"));
        }
        assertFalse(Files.exists(wrongType.resolve(IngotWorldManifest.FILE_NAME)));

        Path reservedStore = world("region-path-is-file");
        Files.writeString(reservedStore.resolve("region"), "not a directory");
        try {
            IngotWorldManager.openWorld(reservedStore);
            fail("A file occupying the region storage path must be refused");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("ambiguous path"));
        }
        assertFalse(Files.exists(reservedStore.resolve(IngotWorldManifest.FILE_NAME)));
    }

    @Test public void detectsExternalChunkFilesOnlyWhenTheyLookLikeAnvilStorageFiles() throws Exception {
        Path world = world("external-chunks");
        marker(world.resolve("region"), "c.-1.42.mcc");
        assertNull(IngotWorldManager.openWorld(world));

        Path unrelated = world("unrelated-mca-names");
        marker(unrelated.resolve("region"), "notes.mca");
        marker(unrelated.resolve("entities"), "readme.mcc");
        IngotWorldManager.Session session = IngotWorldManager.openWorld(unrelated);
        assertNotNull(session);
        IngotWorldManager.closeWorld(session);
    }

    @Test public void ignoresBackupsNestedInsideStorageAndUnrelatedFiles() throws Exception {
        Path root = world("backups");
        marker(root.resolve("region/backups/region"), "r.0.0.mca");
        marker(root.resolve("entities/old-copy"), "r.1.0.mcc");
        marker(root.resolve("misc"), "r.0.0.ir");
        marker(root.resolve("backups/region"), "r.2.0.mca");
        marker(root.resolve("DIMBackup/region"), "r.3.0.mca");

        IngotWorldManager.Session session = IngotWorldManager.openWorld(root);
        assertNotNull(session);
        IngotWorldManager.closeWorld(session);
    }

    @Test public void ignoresUnrelatedSymlinksButRefusesLinksAtActiveStoragePaths() throws Exception {
        Path outside = world("external-target");
        marker(outside.resolve("region"), "r.0.0.mca");
        Path root = world("symlink-backup");
        try {
            Files.createSymbolicLink(root.resolve("backup-link"), outside);
            IngotWorldManager.Session session = IngotWorldManager.openWorld(root);
            assertNotNull(session);
            IngotWorldManager.closeWorld(session);
        } catch (UnsupportedOperationException | SecurityException e) {
            return;
        }

        Path linkedStorage = world("symlink-storage");
        Files.createSymbolicLink(linkedStorage.resolve("region"), outside.resolve("region"));
        try {
            IngotWorldManager.openWorld(linkedStorage);
            fail("A link at an active storage path must be refused");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("symbolic link") || expected.getMessage().contains("ambiguous path"));
        }
        assertFalse(Files.exists(linkedStorage.resolve(IngotWorldManifest.FILE_NAME)));
    }

    @Test public void ingotRegionFilesAreRecognizedOnlyUnderStorageIngotDirectories() throws Exception {
        Path root = world("ingot-files");
        marker(root.resolve("dimensions/example/lol/region/ingot"), "r.-1.2.ir");
        new IngotWorldManifest(UUID.randomUUID()).write(root);

        IngotWorldManager.Session session = IngotWorldManager.openWorld(root);
        assertNotNull(session);
        IngotWorldManager.closeWorld(session);

        Path unrelated = world("unrelated-ir");
        marker(unrelated.resolve("backups/ingot"), "r.0.0.ir");
        marker(unrelated.resolve("region/snapshots"), "r.1.0.ir");
        IngotWorldManager.Session unrelatedSession = IngotWorldManager.openWorld(unrelated);
        assertNotNull(unrelatedSession);
        IngotWorldManager.closeWorld(unrelatedSession);
    }

    @Test public void refusesMixedAnvilAndIngotStorage() throws Exception {
        Path mixed = world("mixed");
        marker(mixed.resolve("DIM-1/entities"), "r.0.0.mca");
        marker(mixed.resolve("dimensions/example/lol/region/ingot"), "r.0.0.ir");
        new IngotWorldManifest(UUID.randomUUID()).write(mixed);

        try {
            IngotWorldManager.openWorld(mixed);
            fail("Mixed active backends must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("coexist") || expected.getMessage().contains("mixed backends"));
        }
    }

    @Test public void refusesIngotDataOrCompactionCandidatesWithoutManifest() throws Exception {
        Path orphan = world("orphan");
        marker(orphan.resolve("region/ingot"), "r.0.0.ir");
        try {
            IngotWorldManager.openWorld(orphan);
            fail("Orphan Ingot data must not fall back to Anvil");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("without an Ingot manifest"));
        }

        Path candidate = world("orphan-candidate");
        marker(candidate.resolve("region/ingot"), "r.0.0.ir.compacting-recovery.lock");
        try {
            IngotWorldManager.openWorld(candidate);
            fail("An interrupted compaction candidate must not fall back to Anvil");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("without an Ingot manifest"));
        }
    }

    @Test public void refusesInterruptedActivationAndDoesNotTouchPendingFile() throws Exception {
        Path root = world("pending");
        Path pending = root.resolve(IngotWorldManifest.FILE_NAME + ".pending");
        Files.writeString(pending, "incomplete");
        try {
            IngotWorldManager.openWorld(root);
            fail("Pending activation must be preserved and refused");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Interrupted Ingot activation"));
        }
        assertEquals("incomplete", Files.readString(pending));
        assertFalse(Files.exists(root.resolve(IngotWorldManifest.FILE_NAME)));
    }

    @Test public void damagedIngotRegionIsNotReclassifiedAsAnvil() throws Exception {
        Path root = world("damaged-region");
        Path region = root.resolve("region/ingot/r.0.0.ir");
        Files.createDirectories(region.getParent());
        Files.write(region, new byte[] {1, 2, 3});
        new IngotWorldManifest(UUID.randomUUID()).write(root);

        IngotWorldManager.Session session = IngotWorldManager.openWorld(root);
        assertNotNull("A valid manifest selects Ingot; corrupt data must fail in the Ingot backend", session);
        IngotStorage storage = new IngotStorage(root.resolve("region/ingot"), StorageOptions.defaults(false),
                CompressionRegistry.defaults(), ignored -> { });
        session.register(storage);
        try {
            storage.read(0, 0);
            fail("A corrupt .ir region must be rejected by the Ingot storage layer");
        } catch (IOException expected) {
            assertTrue(Files.exists(region));
            assertFalse("Corrupt Ingot data must not trigger an Anvil fallback", Files.exists(root.resolve("region/r.0.0.mca")));
        } finally {
            IngotWorldManager.closeWorld(session);
        }
    }

    @Test public void refusesCorruptManifestWithoutSwitchingBackend() throws Exception {
        Path root = world("corrupt-manifest");
        new IngotWorldManifest(UUID.randomUUID()).write(root);
        Path manifest = root.resolve(IngotWorldManifest.FILE_NAME);
        Files.writeString(manifest, Files.readString(manifest).replace("storageMode=authoritative", "storageMode=changed"));
        try {
            IngotWorldManager.openWorld(root);
            fail("A broken manifest must not be treated as an Anvil world");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("checksum"));
        }
    }
}
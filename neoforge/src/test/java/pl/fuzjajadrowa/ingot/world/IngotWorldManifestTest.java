package pl.fuzjajadrowa.ingot.world;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.zip.CRC32C;

import static org.junit.Assert.*;

public class IngotWorldManifestTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void writesAndReadsCurrentManifest() throws Exception {
        Path world = temporary.getRoot().toPath();
        IngotWorldManifest manifest = new IngotWorldManifest(UUID.fromString("d6eae070-6c08-4ba1-8d47-1a996387a4d1"));
        manifest.write(world);
        assertEquals(manifest, IngotWorldManifest.read(world));
        assertTrue(Files.readString(world.resolve(IngotWorldManifest.FILE_NAME)).contains("implementationVersion=0.3.0-alpha\n"));
        assertTrue(Files.readString(world.resolve(IngotWorldManifest.FILE_NAME)).contains("formatVersion=1\n"));
        assertFalse(Files.exists(world.resolve(IngotWorldManifest.FILE_NAME + ".pending")));
    }

    @Test public void acceptsAnOlderImplementationVersionWithoutTreatingItAsFormatVersion() throws Exception {
        Path world = temporary.getRoot().toPath();
        UUID id = UUID.fromString("3e22e47d-b0f1-4f4d-a2be-130ee4b04b46");
        String body = "format=ingot-region\nformatVersion=1\nimplementationVersion=0.1.0\n"
                + "storageMode=authoritative\nminecraft=1.21.1\nregionWidth=32\ncodec=1\nworldId=" + id + "\n";
        Files.writeString(world.resolve(IngotWorldManifest.FILE_NAME), body + "crc32c=" + checksum(body) + "\n");
        assertEquals(new IngotWorldManifest(id), IngotWorldManifest.read(world));
    }

    @Test public void rejectsManifestWithInvalidChecksum() throws Exception {
        Path world = temporary.getRoot().toPath();
        new IngotWorldManifest(UUID.randomUUID()).write(world);
        Path file = world.resolve(IngotWorldManifest.FILE_NAME);
        Files.writeString(file, Files.readString(file).replace("storageMode=authoritative", "storageMode=altered"));
        try {
            IngotWorldManifest.read(world);
            fail("Modified manifest must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("checksum"));
        }
    }

    @Test public void doesNotOverwriteAnExistingManifest() throws Exception {
        Path world = temporary.getRoot().toPath();
        Path file = world.resolve(IngotWorldManifest.FILE_NAME);
        Files.writeString(file, "preserve-this-manifest");
        try {
            new IngotWorldManifest(UUID.randomUUID()).write(world);
            fail("Manifest must never be replaced");
        } catch (IOException expected) { }
        assertEquals("preserve-this-manifest", Files.readString(file));
    }

    @Test public void refusesSymlinkManifestAndLeavesItInPlace() throws Exception {
        Path world = temporary.getRoot().toPath();
        Path target = temporary.newFile("manifest-target").toPath();
        Files.writeString(target, "not-a-manifest");
        Path manifest = world.resolve(IngotWorldManifest.FILE_NAME);
        try {
            Files.createSymbolicLink(manifest, target);
        } catch (UnsupportedOperationException | SecurityException e) {
            return;
        }
        try {
            IngotWorldManifest.read(world);
            fail("Linked manifests must not be followed");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("linked"));
        }
        assertTrue(Files.isSymbolicLink(manifest));
    }

    private static String checksum(String body) {
        CRC32C crc = new CRC32C();
        crc.update(body.getBytes(StandardCharsets.UTF_8));
        return String.format(java.util.Locale.ROOT, "%08x", crc.getValue());
    }
}

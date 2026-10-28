package pl.fuzjajadrowa.ingot.world;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.Assert.*;

public class IngotWorldManifestTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void writesAndReadsCurrentManifest() throws Exception {
        Path world = temporary.getRoot().toPath();
        IngotWorldManifest manifest = new IngotWorldManifest(UUID.fromString("d6eae070-6c08-4ba1-8d47-1a996387a4d1"));
        manifest.write(world);
        assertEquals(manifest, IngotWorldManifest.read(world));
        assertTrue(Files.readString(world.resolve(IngotWorldManifest.FILE_NAME)).contains("implementationVersion=0.3.0-alpha\n"));
    }

    @Test public void rejectsManifestWithInvalidChecksum() throws Exception {
        Path world = temporary.getRoot().toPath();
        new IngotWorldManifest(UUID.randomUUID()).write(world);
        Path file = world.resolve(IngotWorldManifest.FILE_NAME);
        Files.writeString(file, Files.readString(file).replace("storageMode=authoritative", "storageMode=altered"));
        try {
            IngotWorldManifest.read(world);
            fail("Checksum mismatch must be reported");
        } catch (IOException expected) { }
    }

    @Test public void doesNotOverwriteAnExistingManifest() throws Exception {
        Path world = temporary.getRoot().toPath();
        Path file = world.resolve(IngotWorldManifest.FILE_NAME);
        Files.writeString(file, "preserve-this-manifest");
        try {
            new IngotWorldManifest(UUID.randomUUID()).write(world);
            fail("Existing manifest must never be replaced");
        } catch (IOException expected) { }
        assertEquals("preserve-this-manifest", Files.readString(file));
    }
}
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
        assertTrue(Files.readString(world.resolve(IngotWorldManifest.FILE_NAME)).contains("implementationVersion=0.2.0-alpha\n"));
    }

    @Test public void acceptsValidManifestFromVersion010() throws Exception {
        Path world = temporary.getRoot().toPath();
        UUID id = UUID.fromString("4d71ea94-28b4-4ed0-94d5-c5cb40e65cbb");
        String body = "format=ingot-region\nformatVersion=1\nimplementationVersion=0.1.0\n"
                + "storageMode=authoritative\nminecraft=1.21.1\nregionWidth=32\ncodec=1\nworldId=" + id + "\n";
        CRC32C crc = new CRC32C();
        crc.update(body.getBytes(StandardCharsets.UTF_8));
        String document = body + "crc32c=" + String.format(java.util.Locale.ROOT, "%08x", crc.getValue()) + "\n";
        Files.writeString(world.resolve(IngotWorldManifest.FILE_NAME), document, StandardCharsets.UTF_8);
        assertEquals(new IngotWorldManifest(id), IngotWorldManifest.read(world));
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
}
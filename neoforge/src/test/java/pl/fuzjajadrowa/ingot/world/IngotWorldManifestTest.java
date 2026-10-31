package pl.fuzjajadrowa.ingot.world;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.Assert.*;

public class IngotWorldManifestTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void validManifestCanBeReadRepeatedlyButIsNeverReplaced() throws Exception {
        Path root = temporary.newFolder("manifest").toPath();
        IngotWorldManifest first = new IngotWorldManifest(UUID.fromString("61dc11f9-c2fd-4a85-8754-2fd03a88005f"));
        first.write(root);
        byte[] saved = Files.readAllBytes(root.resolve(IngotWorldManifest.FILE_NAME));
        assertEquals(first, IngotWorldManifest.read(root));
        assertEquals(first, IngotWorldManifest.read(root));
        try {
            new IngotWorldManifest(UUID.randomUUID()).write(root);
            fail("A world manifest must not be overwritten");
        } catch (java.io.IOException expected) { }
        assertArrayEquals(saved, Files.readAllBytes(root.resolve(IngotWorldManifest.FILE_NAME)));
        assertFalse(Files.exists(root.resolve(IngotWorldManifest.FILE_NAME + ".pending")));
    }

    @Test public void checksumDetectsManifestMutation() throws Exception {
        Path root = temporary.newFolder("corrupt").toPath();
        new IngotWorldManifest(UUID.randomUUID()).write(root);
        Path manifest = root.resolve(IngotWorldManifest.FILE_NAME);
        Files.writeString(manifest, Files.readString(manifest).replace("codec=1", "codec=2"));
        try {
            IngotWorldManifest.read(root);
            fail("Changed manifest must fail checksum validation");
        } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("checksum")); }
    }
}
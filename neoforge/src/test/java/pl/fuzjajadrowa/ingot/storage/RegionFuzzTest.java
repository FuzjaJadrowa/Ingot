package pl.fuzjajadrowa.ingot.storage;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import pl.fuzjajadrowa.ingot.compression.CompressionRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.Assert.*;

public class RegionFuzzTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void deterministicMutationsAreRejectedOrReportedAsIoErrors() throws Exception {
        final long seed = 0x1a770fL;
        Path source = temporary.newFolder("source").toPath().resolve("r.0.0.ir");
        try (IngotRegionFile region = new IngotRegionFile(source, 0, 0, StorageOptions.defaults(false),
                CompressionRegistry.defaults())) {
            byte[] payload = new byte[512];
            new Random(seed).nextBytes(payload);
            region.write(0, 0, payload);
        }
        byte[] valid = Files.readAllBytes(source);
        Random random = new Random(seed);
        int cases = 300;
        for (int iteration = 0; iteration < cases; iteration++) {
            byte[] mutated = valid.clone();
            int position = random.nextInt(mutated.length);
            mutated[position] ^= (byte) (1 + random.nextInt(255));
            Path path = temporary.getRoot().toPath().resolve("mutation-" + iteration).resolve("r.0.0.ir");
            Files.createDirectories(path.getParent());
            Files.write(path, mutated);
            boolean rejected = false;
            try (IngotRegionFile region = new IngotRegionFile(path, 0, 0, StorageOptions.defaults(false),
                    CompressionRegistry.defaults())) {
                region.read(0, 0);
            } catch (java.io.IOException expected) {
                rejected = true;
            }
            assertTrue("seed=" + seed + " iteration=" + iteration + " byte=" + position, rejected);
        }
    }
}
package pl.fuzjajadrowa.ingot.storage;

import org.junit.Test;

import static org.junit.Assert.*;

public class StorageOptionsTest {
    @Test public void acceptsDocumentedBoundaryValues() {
        assertEquals(1, new StorageOptions(1, 1, 1, false).maxRawBytes());
        assertEquals(1_073_741_824, new StorageOptions(1_073_741_824, 1_073_741_824, 256, true).maxCompressedBytes());
    }

    @Test public void rejectsInvalidByteAndCacheLimits() {
        assertInvalid(0, 1, 1);
        assertInvalid(1, 0, 1);
        assertInvalid(-1, 1, 1);
        assertInvalid(1, -1, 1);
        assertInvalid(1, 1, 0);
        assertInvalid(1, 1, 257);
        assertInvalid(1_073_741_825, 1, 1);
        assertInvalid(1, 1_073_741_825, 1);
    }

    private static void assertInvalid(int raw, int compressed, int cached) {
        try { new StorageOptions(raw, compressed, cached, false); fail("Invalid limits should not be accepted"); }
        catch (IllegalArgumentException expected) { }
    }
}
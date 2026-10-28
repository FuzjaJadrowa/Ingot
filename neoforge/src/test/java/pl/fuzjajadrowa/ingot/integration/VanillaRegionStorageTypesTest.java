package pl.fuzjajadrowa.ingot.integration;

import org.junit.Test;
import static org.junit.Assert.*;

public class VanillaRegionStorageTypesTest {
    @Test public void supportsChunkEntityAndPoiStorageOnly() {
        assertTrue(VanillaRegionStorageTypes.usesIngotStorageType("chunk"));
        assertTrue(VanillaRegionStorageTypes.usesIngotStorageType("entities"));
        assertTrue(VanillaRegionStorageTypes.usesIngotStorageType("poi"));
        assertFalse(VanillaRegionStorageTypes.usesIngotStorageType("region"));
        assertFalse(VanillaRegionStorageTypes.usesIngotStorageType("unknown"));
        assertFalse(VanillaRegionStorageTypes.usesIngotStorageType(null));
    }
}

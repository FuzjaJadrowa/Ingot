package pl.fuzjajadrowa.ingot.integration;

import java.util.Set;

public final class VanillaRegionStorageTypes {
    private static final Set<String> SUPPORTED = Set.of("chunk", "entities", "poi");

    private VanillaRegionStorageTypes() { }

    public static boolean usesIngotStorageType(String type) {
        return type != null && SUPPORTED.contains(type);
    }
}
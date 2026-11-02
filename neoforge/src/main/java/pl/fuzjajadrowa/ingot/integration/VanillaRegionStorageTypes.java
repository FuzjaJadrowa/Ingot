package pl.fuzjajadrowa.ingot.integration;

import pl.fuzjajadrowa.ingot.serialization.IcfPayloadKind;

import java.util.Set;

public final class VanillaRegionStorageTypes {
    private static final Set<String> SUPPORTED = Set.of("chunk", "entities", "poi");

    private VanillaRegionStorageTypes() { }

    public static boolean usesIngotStorageType(String type) {
        return type != null && SUPPORTED.contains(type);
    }

    public static IcfPayloadKind payloadKind(String type) {
        return switch (type) {
            case "chunk" -> IcfPayloadKind.CHUNK;
            case "entities" -> IcfPayloadKind.ENTITIES;
            case "poi" -> IcfPayloadKind.POI;
            default -> throw new IllegalArgumentException("Unsupported Minecraft region storage type: " + type);
        };
    }
}
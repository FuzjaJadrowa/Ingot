package pl.fuzjajadrowa.ingot.storage;

import pl.fuzjajadrowa.ingot.compression.CompressionRegistry;

import java.nio.file.Path;

public final class CrashProcess {
    private CrashProcess() { }

    public static void main(String[] args) throws Exception {
        String action = args[0];
        RegionFaultInjector.Point stopAt = RegionFaultInjector.Point.valueOf(args[1]);
        Path regionPath = Path.of(args[2]);
        boolean sync = Boolean.parseBoolean(args[3]);
        StorageOptions options = new StorageOptions(1024 * 1024, 1024 * 1024, 4, sync);
        RegionFaultInjector injector = point -> {
            if (point == stopAt) Runtime.getRuntime().halt(73);
        };
        try (IngotRegionFile region = new IngotRegionFile(regionPath, 0, 0, options,
                CompressionRegistry.defaults(), injector)) {
            if (action.equals("write")) {
                region.write(0, 0, "NEW committed payload".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } else if (action.equals("compact")) {
                region.compactTo(regionPath.resolveSibling(regionPath.getFileName() + ".compacting-child"));
            } else {
                throw new IllegalArgumentException("Unknown crash action: " + action);
            }
        }
        throw new AssertionError("Requested crash checkpoint was not reached: " + stopAt);
    }
}
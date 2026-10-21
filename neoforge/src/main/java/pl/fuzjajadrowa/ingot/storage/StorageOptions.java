package pl.fuzjajadrowa.ingot.storage;

public record StorageOptions(int maxRawBytes, int maxCompressedBytes, int cachedRegions, boolean sync) {
    public StorageOptions {
        if (maxRawBytes < 1 || maxRawBytes > 1_073_741_824
                || maxCompressedBytes < 1 || maxCompressedBytes > 1_073_741_824
                || cachedRegions < 1 || cachedRegions > 256) {
            throw new IllegalArgumentException("Invalid Ingot storage limits");
        }
    }

    public static StorageOptions defaults(boolean sync) {
        return new StorageOptions(64 * 1024 * 1024, 65 * 1024 * 1024, 32, sync);
    }
}
package pl.fuzjajadrowa.ingot.compression;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class CompressionRegistry {
    private final Map<Integer, CompressionCodec> codecs;

    public CompressionRegistry(CompressionCodec... codecs) {
        Map<Integer, CompressionCodec> entries = new HashMap<>();
        for (CompressionCodec codec : codecs) {
            if (codec.id() < 1 || codec.id() > 255 || entries.putIfAbsent(codec.id(), codec) != null) {
                throw new IllegalArgumentException("Invalid/duplicate codec ID " + codec.id());
            }
        }
        this.codecs = Map.copyOf(entries);
    }

    public static CompressionRegistry defaults() { return new CompressionRegistry(new ZstdCodec(3)); }

    public CompressionCodec require(int id) throws IOException {
        CompressionCodec codec = codecs.get(id);
        if (codec == null) throw new IOException("Unsupported Ingot compression ID " + id);
        return codec;
    }
}
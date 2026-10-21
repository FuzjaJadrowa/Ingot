package pl.fuzjajadrowa.ingot.compression;

import com.github.luben.zstd.Zstd;
import com.github.luben.zstd.ZstdException;
import java.io.IOException;

public final class ZstdCodec implements CompressionCodec {
    private final int level;

    public ZstdCodec(int level) {
        if (level < -5 || level > 22) throw new IllegalArgumentException("Zstd level outside [-5,22]");
        this.level = level;
    }

    @Override public int id() { return 1; }

    @Override public byte[] compress(byte[] input) throws IOException {
        try {
            return Zstd.compress(input, level);
        } catch (ZstdException e) {
            throw new IOException("Zstd compression failed", e);
        }
    }

    @Override public byte[] decompress(byte[] input, int expectedLength) throws IOException {
        if (expectedLength < 0) throw new IOException("Negative decompressed length");
        try {
            byte[] result = new byte[expectedLength];
            long actual = Zstd.decompress(result, input);
            if (Zstd.isError(actual) || actual != expectedLength) {
                throw new IOException("Invalid Zstd data/length: " + actual + ", expected " + expectedLength);
            }
            return result;
        } catch (ZstdException e) {
            throw new IOException("Zstd decompression failed", e);
        }
    }
}
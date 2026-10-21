package pl.fuzjajadrowa.ingot.compression;

import java.io.IOException;

public interface CompressionCodec {
    int id();
    byte[] compress(byte[] input) throws IOException;
    byte[] decompress(byte[] input, int expectedLength) throws IOException;
}
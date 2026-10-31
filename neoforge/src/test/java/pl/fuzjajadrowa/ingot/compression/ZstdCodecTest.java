package pl.fuzjajadrowa.ingot.compression;

import org.junit.Test;

import java.io.IOException;
import java.util.Random;

import static org.junit.Assert.*;

public class ZstdCodecTest {
    @Test public void roundTripsBoundaryAndRepresentativePayloadsAcrossLevels() throws Exception {
        for (int level : new int[] {1, 3, 6, 9}) {
            ZstdCodec codec = new ZstdCodec(level);
            byte[][] payloads = {
                    new byte[0], {0}, {1, 2, 3, 4},
                    repeated(128 * 1024, (byte) 0x5a),
                    random(128 * 1024, 0x51a7 + level)
            };
            for (byte[] payload : payloads) {
                byte[] compressed = codec.compress(payload);
                assertArrayEquals("level=" + level + " raw=" + payload.length,
                        payload, codec.decompress(compressed, payload.length));
            }
        }
    }

    @Test public void rejectsMalformedFramesAndWrongExpectedLengths() throws Exception {
        ZstdCodec codec = new ZstdCodec(3);
        try { codec.decompress(new byte[] {1, 2, 3}, 100); fail("Malformed frame must be rejected"); }
        catch (IOException expected) { }
        byte[] compressed = codec.compress(new byte[] {1, 2, 3});
        try { codec.decompress(compressed, 4); fail("Wrong decoded length must be rejected"); }
        catch (IOException expected) { }
        try { codec.decompress(compressed, -1); fail("Negative decoded length must be rejected"); }
        catch (IOException expected) { }
    }

    @Test public void repeatedlyUsesCodecWithoutCrossCallState() throws Exception {
        ZstdCodec codec = new ZstdCodec(3);
        for (int i = 0; i < 100; i++) {
            byte[] payload = random(i * 17, 0xabc0 + i);
            assertArrayEquals(payload, codec.decompress(codec.compress(payload), payload.length));
        }
    }

    private static byte[] repeated(int length, byte value) { byte[] bytes = new byte[length]; java.util.Arrays.fill(bytes, value); return bytes; }
    private static byte[] random(int length, long seed) { byte[] bytes = new byte[length]; new Random(seed).nextBytes(bytes); return bytes; }
}
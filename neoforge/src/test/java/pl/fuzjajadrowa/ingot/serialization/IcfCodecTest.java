package pl.fuzjajadrowa.ingot.serialization;

import net.minecraft.nbt.CompoundTag;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public class IcfCodecTest {
    @Test public void encodesAndDecodesEachPayloadKind() throws Exception {
        for (IcfPayloadKind kind : IcfPayloadKind.values()) {
            CompoundTag expected = new CompoundTag();
            expected.putString("kind", kind.name());
            expected.putLong("value", 0x123456789abcdef0L);

            byte[] payload = IcfCodec.encode(expected, kind, 1024);
            assertTrue(IcfCodec.isIcfPayload(payload));
            assertEquals(expected, IcfCodec.decode(payload, kind, NbtBinaryCodec.Limits.forRawBytes(1024)));
        }
    }

    @Test public void rejectsWrongKindUnsupportedVersionFlagsAndMalformedLengths() throws Exception {
        byte[] valid = IcfCodec.encode(new CompoundTag(), IcfPayloadKind.CHUNK, 128);

        try { IcfCodec.decode(valid, IcfPayloadKind.POI, NbtBinaryCodec.Limits.forRawBytes(128)); fail("Kind mismatch must fail"); }
        catch (IOException expected) { }

        byte[] unsupportedVersion = valid.clone();
        unsupportedVersion[5] = 2;
        assertRejected(unsupportedVersion);

        byte[] unsupportedFlags = valid.clone();
        unsupportedFlags[7] = 1;
        assertRejected(unsupportedFlags);

        byte[] truncated = java.util.Arrays.copyOf(valid, valid.length - 1);
        assertRejected(truncated);

        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);
        assertRejected(trailing);
    }

    @Test public void rejectsPayloadsAboveTheConfiguredLimitBeforeDecoding() throws Exception {
        byte[] payload = IcfCodec.encode(new CompoundTag(), IcfPayloadKind.CHUNK, 128);
        try { IcfCodec.decode(payload, IcfPayloadKind.CHUNK, NbtBinaryCodec.Limits.forRawBytes(payload.length - 1)); fail("Payload limit must be enforced"); }
        catch (IOException expected) { }
    }

    private static void assertRejected(byte[] payload) throws Exception {
        try {
            IcfCodec.decode(payload, IcfPayloadKind.CHUNK, NbtBinaryCodec.Limits.forRawBytes(128));
            fail("Malformed ICF payload must be rejected");
        } catch (IOException expected) { }
    }
}
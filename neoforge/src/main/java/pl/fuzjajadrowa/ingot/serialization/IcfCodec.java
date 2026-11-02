package pl.fuzjajadrowa.ingot.serialization;

import net.minecraft.nbt.CompoundTag;

import java.io.IOException;
import java.util.Objects;

public final class IcfCodec {
    private static final int MINIMUM_ENVELOPE_BYTES = 9;

    private IcfCodec() { }

    public static byte[] encode(CompoundTag tag, IcfPayloadKind kind, int maxPayloadBytes) throws IOException {
        Objects.requireNonNull(tag, "tag");
        Objects.requireNonNull(kind, "kind");
        if (maxPayloadBytes < MINIMUM_ENVELOPE_BYTES) {
            throw new IOException("Configured limit is too small for an ICF payload");
        }

        byte[] body = NbtBinaryCodec.encode(tag, maxPayloadBytes - MINIMUM_ENVELOPE_BYTES);
        byte[] payload = IcfFormat.encode(kind, body, maxPayloadBytes);
        if (payload.length > maxPayloadBytes) {
            throw new IOException("ICF payload exceeds configured byte limit");
        }
        return payload;
    }

    public static CompoundTag decode(byte[] payload, IcfPayloadKind expectedKind,
                                      NbtBinaryCodec.Limits limits) throws IOException {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(expectedKind, "expectedKind");
        Objects.requireNonNull(limits, "limits");
        if (payload.length > limits.maxBytes()) throw new IOException("ICF payload exceeds configured byte limit");
        byte[] body = IcfFormat.decode(payload, expectedKind, limits.maxBytes());
        return NbtBinaryCodec.decode(body, limits);
    }

    public static boolean isIcfPayload(byte[] payload) {
        return IcfFormat.hasMagic(payload);
    }
}
package pl.fuzjajadrowa.ingot.serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

public final class IcfFormat {
    private static final byte[] MAGIC = {'I', 'C', 'F', 0};
    public static final int VERSION = 1;
    private static final int FIXED_HEADER_SIZE = 8;

    private IcfFormat() { }

    public static boolean hasMagic(byte[] bytes) {
        if (bytes == null || bytes.length < MAGIC.length) return false;
        for (int i = 0; i < MAGIC.length; i++) {
            if (bytes[i] != MAGIC[i]) return false;
        }
        return true;
    }

    public static byte[] encode(IcfPayloadKind kind, byte[] body, int maxBodyBytes) throws IOException {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(body, "body");
        if (maxBodyBytes < 0) throw new IllegalArgumentException("Negative ICF body limit");
        if (body.length > maxBodyBytes) throw new IOException("ICF body exceeds configured byte limit");

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(MAGIC);
        output.write((VERSION >>> 8) & 0xff);
        output.write(VERSION & 0xff);
        output.write(kind.id());
        output.write(0);
        writeUnsignedVarInt(output, body.length);
        output.write(body);
        return output.toByteArray();
    }

    public static byte[] decode(byte[] payload, IcfPayloadKind expectedKind, int maxBodyBytes) throws IOException {
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(expectedKind, "expectedKind");
        if (maxBodyBytes < 0) throw new IllegalArgumentException("Negative ICF body limit");
        if (payload.length < FIXED_HEADER_SIZE) throw new IOException("Truncated ICF header");
        if (!hasMagic(payload)) throw new IOException("Invalid ICF magic");

        int version = (Byte.toUnsignedInt(payload[4]) << 8) | Byte.toUnsignedInt(payload[5]);
        if (version != VERSION) throw new IOException("Unsupported ICF format version " + version);

        IcfPayloadKind actualKind = IcfPayloadKind.fromId(Byte.toUnsignedInt(payload[6]));
        if (actualKind != expectedKind) {
            throw new IOException("ICF payload kind mismatch: expected " + expectedKind + ", found " + actualKind);
        }
        if (payload[7] != 0) throw new IOException("Unsupported ICF v1 flags " + Byte.toUnsignedInt(payload[7]));

        VarInt length = readUnsignedVarInt(payload, FIXED_HEADER_SIZE);
        if (length.value() > maxBodyBytes) throw new IOException("ICF body exceeds configured byte limit");
        if (length.value() != payload.length - length.nextOffset()) {
            throw new IOException(length.value() > payload.length - length.nextOffset()
                    ? "Truncated ICF body" : "Unexpected trailing data after ICF body");
        }
        return Arrays.copyOfRange(payload, length.nextOffset(), payload.length);
    }

    private static void writeUnsignedVarInt(ByteArrayOutputStream output, int value) {
        while ((value & ~0x7f) != 0) {
            output.write((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        output.write(value);
    }

    private static VarInt readUnsignedVarInt(byte[] input, int offset) throws IOException {
        int value = 0;
        for (int index = 0; index < 5; index++) {
            int position = offset + index;
            if (position >= input.length) throw new IOException("Truncated ICF body length");
            int current = Byte.toUnsignedInt(input[position]);
            if (index == 4 && (current & 0xf0) != 0) throw new IOException("ICF body length overflows 32 bits");
            value |= (current & 0x7f) << (index * 7);
            if ((current & 0x80) == 0) {
                if (index > 0 && (current & 0x7f) == 0) throw new IOException("Non-canonical ICF body length");
                if (value < 0) throw new IOException("ICF body length exceeds supported range");
                return new VarInt(value, position + 1);
            }
        }
        throw new IOException("Malformed ICF body length VarInt");
    }

    private record VarInt(int value, int nextOffset) { }
}
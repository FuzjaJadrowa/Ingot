package pl.fuzjajadrowa.ingot.serialization;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.ShortTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class NbtBinaryCodec {
    private static final int END = 0;
    private static final int BYTE = 1;
    private static final int SHORT = 2;
    private static final int INT = 3;
    private static final int LONG = 4;
    private static final int FLOAT = 5;
    private static final int DOUBLE = 6;
    private static final int BYTE_ARRAY = 7;
    private static final int STRING = 8;
    private static final int LIST = 9;
    private static final int COMPOUND = 10;
    private static final int INT_ARRAY = 11;
    private static final int LONG_ARRAY = 12;
    private static final byte[] CURRENT_ENCODING_MAGIC = {'N', 'B', 'T', 2};

    public record Limits(int maxBytes, int maxDepth, int maxNodes, int maxStringChars) {
        public Limits {
            if (maxBytes < 1 || maxDepth < 1 || maxNodes < 1 || maxStringChars < 0) {
                throw new IllegalArgumentException("Invalid ICF NBT limits");
            }
        }

        public static Limits forRawBytes(int maxBytes) {
            return new Limits(maxBytes, 256, Math.max(1, maxBytes / 8), maxBytes / 2);
        }
    }

    private NbtBinaryCodec() { }

    public static byte[] encode(CompoundTag root, int maxBytes) throws IOException {
        Objects.requireNonNull(root, "root");
        if (maxBytes < 1) throw new IllegalArgumentException("Invalid maximum byte count");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream output = new DataOutputStream(new BoundedOutputStream(bytes, maxBytes));
        output.write(CURRENT_ENCODING_MAGIC);
        writeCompound(root, output, 0, new WriteState(maxBytes));
        output.flush();
        return bytes.toByteArray();
    }

    public static CompoundTag decode(byte[] bytes, Limits limits) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        Objects.requireNonNull(limits, "limits");
        if (bytes.length > limits.maxBytes()) throw new IOException("ICF NBT exceeds configured byte limit");
        if (!hasCurrentEncodingMagic(bytes)) throw new IOException("Unsupported ICF NBT body encoding");
        Reader reader = new Reader(bytes, limits);
        CompoundTag root = reader.readCompound(0);
        if (!reader.atEnd()) throw new IOException("Unexpected trailing bytes after ICF NBT compound");
        return root;
    }

    private static void writeCompound(CompoundTag compound, DataOutputStream output, int depth, WriteState state)
            throws IOException {
        state.checkDepth(depth);
        List<String> names = new ArrayList<>(compound.getAllKeys());
        names.sort(String::compareTo);
        for (String name : names) {
            Tag value = compound.get(name);
            if (value == null || value.getId() == END) throw new IOException("Invalid EndTag compound entry");
            state.addNode();
            output.writeByte(value.getId());
            writeString(name, output, state.maxBytes());
            writeTagPayload(value, output, depth + 1, state);
        }
        output.writeByte(END);
    }

    private static void writeTagPayload(Tag tag, DataOutputStream output, int depth, WriteState state)
            throws IOException {
        state.checkDepth(depth);
        switch (tag.getId()) {
            case BYTE -> output.writeByte(((NumericTag) tag).getAsByte());
            case SHORT -> output.writeShort(((NumericTag) tag).getAsShort());
            case INT -> output.writeInt(((NumericTag) tag).getAsInt());
            case LONG -> output.writeLong(((NumericTag) tag).getAsLong());
            case FLOAT -> output.writeInt(Float.floatToRawIntBits(((NumericTag) tag).getAsFloat()));
            case DOUBLE -> output.writeLong(Double.doubleToRawLongBits(((NumericTag) tag).getAsDouble()));
            case BYTE_ARRAY -> {
                byte[] value = ((ByteArrayTag) tag).getAsByteArray();
                writeLength(value.length, output);
                output.write(value);
            }
            case STRING -> writeString(((StringTag) tag).getAsString(), output, state.maxBytes());
            case LIST -> writeList((ListTag) tag, output, depth, state);
            case COMPOUND -> writeCompound((CompoundTag) tag, output, depth, state);
            case INT_ARRAY -> {
                int[] value = ((IntArrayTag) tag).getAsIntArray();
                writeLength(value.length, output);
                for (int element : value) output.writeInt(element);
            }
            case LONG_ARRAY -> {
                long[] value = ((LongArrayTag) tag).getAsLongArray();
                writeLength(value.length, output);
                for (long element : value) output.writeLong(element);
            }
            default -> throw new IOException("Unsupported NBT tag type " + Byte.toUnsignedInt(tag.getId()));
        }
    }

    private static void writeList(ListTag list, DataOutputStream output, int depth, WriteState state)
            throws IOException {
        int elementType = Byte.toUnsignedInt(list.getElementType());
        int count = list.size();
        if (elementType > LONG_ARRAY || (count > 0 && elementType == END)) {
            throw new IOException("Invalid NBT list element type");
        }
        output.writeByte(elementType);
        writeLength(count, output);
        for (int index = 0; index < count; index++) {
            state.addNode();
            Tag value = list.get(index);
            if (Byte.toUnsignedInt(value.getId()) != elementType) throw new IOException("Heterogeneous NBT list");
            writeTagPayload(value, output, depth + 1, state);
        }
    }

    private static void writeString(String value, DataOutputStream output, int maxBytes) throws IOException {
        long encodedBytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char unit = value.charAt(index);
            encodedBytes += unit <= 0x7f ? 1 : unit <= 0x7ff ? 2 : 3;
            if (encodedBytes > maxBytes || value.length() > maxBytes / 2) {
                throw new IOException("ICF NBT string exceeds byte limit");
            }
        }
        writeLength((int) encodedBytes, output);
        for (int index = 0; index < value.length(); index++) {
            int unit = value.charAt(index);
            if (unit <= 0x7f) {
                output.writeByte(unit);
            } else if (unit <= 0x7ff) {
                output.writeByte(0xc0 | (unit >>> 6));
                output.writeByte(0x80 | (unit & 0x3f));
            } else {
                output.writeByte(0xe0 | (unit >>> 12));
                output.writeByte(0x80 | ((unit >>> 6) & 0x3f));
                output.writeByte(0x80 | (unit & 0x3f));
            }
        }
    }

    private static boolean hasCurrentEncodingMagic(byte[] bytes) {
        if (bytes.length < CURRENT_ENCODING_MAGIC.length) return false;
        for (int index = 0; index < CURRENT_ENCODING_MAGIC.length; index++) {
            if (bytes[index] != CURRENT_ENCODING_MAGIC[index]) return false;
        }
        return true;
    }

    private static void writeLength(int length, DataOutputStream output) throws IOException {
        if (length < 0) throw new IOException("Negative ICF NBT length");
        int value = length;
        while ((value & ~0x7f) != 0) {
            output.writeByte((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        output.writeByte(value);
    }

    private static final class WriteState {
        private final int maxBytes;
        private int nodes;

        private WriteState(int maxBytes) { this.maxBytes = maxBytes; }
        private int maxBytes() { return maxBytes; }
        private void checkDepth(int depth) throws IOException {
            if (depth > 256) throw new IOException("ICF NBT nesting exceeds maximum depth");
        }
        private void addNode() throws IOException {
            if (++nodes > Math.max(1, maxBytes / 8)) throw new IOException("ICF NBT exceeds maximum tag count");
        }
    }

    private static final class Reader {
        private final byte[] bytes;
        private final Limits limits;
        private int offset;
        private int nodes;

        private Reader(byte[] bytes, Limits limits) {
            this.bytes = bytes;
            this.limits = limits;
            this.offset = CURRENT_ENCODING_MAGIC.length;
        }
        private boolean atEnd() { return offset == bytes.length; }

        private CompoundTag readCompound(int depth) throws IOException {
            checkDepth(depth);
            CompoundTag compound = new CompoundTag();
            while (true) {
                int type = readUnsignedByte();
                if (type == END) return compound;
                if (type > LONG_ARRAY) throw new IOException("Unknown NBT tag type " + type);
                addNode();
                String name = readString();
                compound.put(name, readTagPayload(type, depth + 1));
            }
        }

        private Tag readTagPayload(int type, int depth) throws IOException {
            checkDepth(depth);
            return switch (type) {
                case BYTE -> ByteTag.valueOf((byte) readUnsignedByte());
                case SHORT -> ShortTag.valueOf((short) readUnsignedShort());
                case INT -> IntTag.valueOf(readInt());
                case LONG -> LongTag.valueOf(readLong());
                case FLOAT -> FloatTag.valueOf(Float.intBitsToFloat(readInt()));
                case DOUBLE -> DoubleTag.valueOf(Double.longBitsToDouble(readLong()));
                case BYTE_ARRAY -> new ByteArrayTag(readByteArray(readLength()));
                case STRING -> StringTag.valueOf(readString());
                case LIST -> readList(depth);
                case COMPOUND -> readCompound(depth);
                case INT_ARRAY -> new IntArrayTag(readIntArray(readLength()));
                case LONG_ARRAY -> new LongArrayTag(readLongArray(readLength()));
                default -> throw new IOException("Unsupported NBT tag type " + type);
            };
        }

        private ListTag readList(int depth) throws IOException {
            int elementType = readUnsignedByte();
            int count = readLength();
            if (elementType > LONG_ARRAY || (count > 0 && elementType == END)) {
                throw new IOException("Invalid NBT list element type or length");
            }
            if (count > limits.maxNodes() - nodes) throw new IOException("ICF NBT list exceeds maximum tag count");
            if (count > bytes.length - offset) throw new IOException("Truncated ICF NBT list");
            ListTag result;
            if (count == 0 && elementType != END) {
                // Minecraft public ListTag constructor cannot set the element type of an empty list.
                byte[] emptyList = {(byte) elementType, 0, 0, 0, 0};
                result = ListTag.TYPE.load(new DataInputStream(new ByteArrayInputStream(emptyList)), NbtAccounter.unlimitedHeap());
                return result;
            }
            result = new ListTag(count);
            for (int index = 0; index < count; index++) {
                addNode();
                Tag value = readTagPayload(elementType, depth + 1);
                if (!result.addTag(index, value)) throw new IOException("Invalid heterogeneous NBT list");
            }
            return result;
        }

        private String readString() throws IOException {
            int length = readLength();
            requireBytes(length);
            int chars = decodeUtf8CodeUnits(offset, length, null);
            if (chars > limits.maxStringChars()) {
                throw new IOException("ICF NBT string exceeds configured character limit");
            }
            char[] value = new char[chars];
            decodeUtf8CodeUnits(offset, length, value);
            offset += length;
            return new String(value);
        }

        private int decodeUtf8CodeUnits(int start, int length, char[] output) throws IOException {
            int end = start + length;
            int cursor = start;
            int chars = 0;
            while (cursor < end) {
                int first = Byte.toUnsignedInt(bytes[cursor++]);
                int value;
                if (first <= 0x7f) {
                    value = first;
                } else if (first >= 0xc2 && first <= 0xdf) {
                    if (cursor >= end) throw new IOException("Truncated ICF NBT string sequence");
                    int second = continuation(bytes[cursor++]);
                    value = ((first & 0x1f) << 6) | second;
                } else if (first >= 0xe0 && first <= 0xef) {
                    if (cursor + 1 >= end) throw new IOException("Truncated ICF NBT string sequence");
                    int secondByte = Byte.toUnsignedInt(bytes[cursor++]);
                    if ((secondByte & 0xc0) != 0x80 || (first == 0xe0 && secondByte < 0xa0)) {
                        throw new IOException("Malformed ICF NBT string sequence");
                    }
                    int third = continuation(bytes[cursor++]);
                    value = ((first & 0x0f) << 12) | ((secondByte & 0x3f) << 6) | third;
                } else {
                    throw new IOException("Malformed ICF NBT string sequence");
                }
                if (output != null) output[chars] = (char) value;
                chars++;
            }
            return chars;
        }

        private int continuation(byte value) throws IOException {
            int unsigned = Byte.toUnsignedInt(value);
            if ((unsigned & 0xc0) != 0x80) throw new IOException("Malformed ICF NBT string continuation");
            return unsigned & 0x3f;
        }

        private byte[] readByteArray(int length) throws IOException {
            requireBytes(length);
            byte[] result = Arrays.copyOfRange(bytes, offset, offset + length);
            offset += length;
            return result;
        }

        private int[] readIntArray(int length) throws IOException {
            requireBytes((long) length * Integer.BYTES);
            int[] result = new int[length];
            for (int index = 0; index < length; index++) result[index] = readInt();
            return result;
        }

        private long[] readLongArray(int length) throws IOException {
            requireBytes((long) length * Long.BYTES);
            long[] result = new long[length];
            for (int index = 0; index < length; index++) result[index] = readLong();
            return result;
        }

        private int readLength() throws IOException {
            int value = 0;
            for (int index = 0; index < 5; index++) {
                int current = readUnsignedByte();
                if (index == 4 && (current & 0xf0) != 0) throw new IOException("ICF NBT length overflows 32 bits");
                value |= (current & 0x7f) << (index * 7);
                if ((current & 0x80) == 0) {
                    if (index > 0 && (current & 0x7f) == 0) throw new IOException("Non-canonical ICF NBT length");
                    if (value < 0) throw new IOException("ICF NBT length exceeds supported range");
                    return value;
                }
            }
            throw new IOException("Malformed ICF NBT length VarInt");
        }

        private int readUnsignedByte() throws IOException {
            requireBytes(1);
            return Byte.toUnsignedInt(bytes[offset++]);
        }

        private int readUnsignedShort() throws IOException {
            requireBytes(2);
            return (Byte.toUnsignedInt(bytes[offset++]) << 8) | Byte.toUnsignedInt(bytes[offset++]);
        }

        private int readInt() throws IOException {
            requireBytes(4);
            return (bytes[offset++] << 24) | (Byte.toUnsignedInt(bytes[offset++]) << 16)
                    | (Byte.toUnsignedInt(bytes[offset++]) << 8) | Byte.toUnsignedInt(bytes[offset++]);
        }

        private long readLong() throws IOException {
            return (Integer.toUnsignedLong(readInt()) << 32) | Integer.toUnsignedLong(readInt());
        }

        private void addNode() throws IOException {
            if (++nodes > limits.maxNodes()) throw new IOException("ICF NBT exceeds configured tag count");
        }

        private void checkDepth(int depth) throws IOException {
            if (depth > limits.maxDepth()) throw new IOException("ICF NBT nesting exceeds configured maximum depth");
        }

        private void requireBytes(long count) throws IOException {
            if (count < 0 || count > bytes.length - (long) offset) throw new IOException("Truncated ICF NBT payload");
        }
    }

    private static final class BoundedOutputStream extends OutputStream {
        private final ByteArrayOutputStream target;
        private final int limit;
        private int count;

        private BoundedOutputStream(ByteArrayOutputStream target, int limit) {
            this.target = target;
            this.limit = limit;
        }

        @Override public void write(int value) throws IOException {
            reserve(1);
            target.write(value);
        }

        @Override public void write(byte[] data, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, data.length);
            reserve(length);
            target.write(data, offset, length);
        }

        private void reserve(int length) throws IOException {
            if (length < 0 || length > limit - count) throw new IOException("ICF NBT exceeds configured byte limit");
            count += length;
        }
    }
}
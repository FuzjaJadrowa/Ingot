package pl.fuzjajadrowa.ingot.serialization;

import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.StringTag;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Random;

import static org.junit.Assert.*;

public class NbtBinaryCodecTest {
    @Test public void deterministicRandomTreesRoundTripAndMutationsFailSafely() throws Exception {
        long seed = 0x4943465f5631L;
        Random random = new Random(seed);
        NbtBinaryCodec.Limits limits = NbtBinaryCodec.Limits.forRawBytes(64 * 1024);

        try {
            for (int sample = 0; sample < 256; sample++) {
                CompoundTag original = randomCompound(random, 0);
                byte[] encoded = NbtBinaryCodec.encode(original, limits.maxBytes());
                assertEquals("seed=" + seed + ", sample=" + sample, original,
                        NbtBinaryCodec.decode(encoded, limits));

                for (int mutation = 0; mutation < 2; mutation++) {
                    byte[] damaged = encoded.clone();
                    int index = random.nextInt(damaged.length);
                    damaged[index] ^= (byte) (1 << random.nextInt(8));
                    try {
                        NbtBinaryCodec.decode(damaged, limits);
                    } catch (IOException expected) {
                        // Unknown mutations are rejected by the codec
                    }
                }
            }
        } catch (AssertionError | RuntimeException failure) {
            throw new AssertionError("Deterministic NBT fuzz failed with seed=" + seed, failure);
        }
    }

    private static CompoundTag randomCompound(Random random, int depth) {
        CompoundTag compound = new CompoundTag();
        int entries = random.nextInt(6);
        for (int index = 0; index < entries; index++) {
            String key = "k" + index;
            switch (random.nextInt(depth < 4 ? 8 : 7)) {
                case 0: compound.putByte(key, (byte) random.nextInt()); break;
                case 1: compound.putInt(key, random.nextInt()); break;
                case 2: compound.putLong(key, random.nextLong()); break;
                case 3: compound.putString(key, "s" + random.nextInt() + "☃"); break;
                case 4: compound.putByteArray(key, new byte[random.nextInt(24)]); break;
                case 5:
                    ListTag strings = new ListTag();
                    for (int item = random.nextInt(5); item > 0; item--) {
                        strings.add(StringTag.valueOf("v" + random.nextInt(1000)));
                    }
                    compound.put(key, strings);
                    break;
                case 6: compound.putIntArray(key, new int[random.nextInt(12)]); break;
                default: compound.put(key, randomCompound(random, depth + 1)); break;
            }
        }
        return compound;
    }

    @Test public void roundTripsEveryTagTypeAndPreservesFloatingPointBitsAndTypedEmptyLists() throws Exception {
        CompoundTag root = new CompoundTag();
        root.putByte("byte", (byte) 0xff);
        root.putShort("short", (short) -1234);
        root.putInt("int", Integer.MIN_VALUE);
        root.putLong("long", Long.MAX_VALUE);
        root.putFloat("float", Float.intBitsToFloat(0x7fc00001));
        root.putDouble("double", Double.longBitsToDouble(0x7ff8000000000001L));
        root.put("bytes", new ByteArrayTag(new byte[] {0, -1, 23}));
        root.putString("unicode", new String(new char[] {
                'u', 'n', 'i', 'c', 'o', 'd', 'e', '-', (char) 0x96ea, (char) 0x2603, '-',
                (char) 0xd83d, (char) 0xde00, '-', (char) 0xd800
        }));
        root.putIntArray("ints", new int[] {Integer.MIN_VALUE, 0, Integer.MAX_VALUE});
        root.putLongArray("longs", new long[] {Long.MIN_VALUE, 0, Long.MAX_VALUE});

        ListTag list = new ListTag();
        list.add(StringTag.valueOf("first"));
        list.add(StringTag.valueOf("second"));
        root.put("list", list);
        CompoundTag nested = new CompoundTag();
        nested.putBoolean("flag", true);
        root.put("nested", nested);
        root.put("typedEmpty", typedEmptyList(3));

        byte[] encoded = NbtBinaryCodec.encode(root, 1024 * 1024);
        CompoundTag decoded = NbtBinaryCodec.decode(encoded, NbtBinaryCodec.Limits.forRawBytes(1024 * 1024));

        assertEquals(root.getByte("byte"), decoded.getByte("byte"));
        assertEquals(root.getShort("short"), decoded.getShort("short"));
        assertEquals(root.getInt("int"), decoded.getInt("int"));
        assertEquals(root.getLong("long"), decoded.getLong("long"));
        assertArrayEquals(root.getByteArray("bytes"), decoded.getByteArray("bytes"));
        assertEquals(root.getString("unicode"), decoded.getString("unicode"));
        assertArrayEquals(root.getIntArray("ints"), decoded.getIntArray("ints"));
        assertArrayEquals(root.getLongArray("longs"), decoded.getLongArray("longs"));
        assertEquals(root.getList("list", 8), decoded.getList("list", 8));
        assertEquals(root.getCompound("nested"), decoded.getCompound("nested"));
        assertEquals(0x7fc00001, Float.floatToRawIntBits(decoded.getFloat("float")));
        assertEquals(0x7ff8000000000001L, Double.doubleToRawLongBits(decoded.getDouble("double")));
        assertEquals(3, Byte.toUnsignedInt(decoded.getList("typedEmpty", 3).getElementType()));
        assertEquals(2, decoded.getList("list", 8).size());
    }

    @Test public void roundTripsLargeArraysWithinTheConfiguredLimit() throws Exception {
        CompoundTag root = new CompoundTag();
        byte[] bytes = new byte[256 * 1024];
        int[] ints = new int[16 * 1024];
        long[] longs = new long[8 * 1024];
        for (int index = 0; index < bytes.length; index++) bytes[index] = (byte) index;
        for (int index = 0; index < ints.length; index++) ints[index] = index * 31;
        for (int index = 0; index < longs.length; index++) longs[index] = (long) index << 40;
        root.put("bytes", new ByteArrayTag(bytes));
        root.put("ints", new IntArrayTag(ints));
        root.put("longs", new LongArrayTag(longs));

        byte[] encoded = NbtBinaryCodec.encode(root, 1024 * 1024);
        CompoundTag decoded = NbtBinaryCodec.decode(encoded, NbtBinaryCodec.Limits.forRawBytes(1024 * 1024));
        assertArrayEquals(bytes, decoded.getByteArray("bytes"));
        assertArrayEquals(ints, decoded.getIntArray("ints"));
        assertArrayEquals(longs, decoded.getLongArray("longs"));
    }

    @Test public void compactStringEncodingShrinksAscii() throws Exception {
        CompoundTag current = new CompoundTag();
        current.putString("value", "abcdefghijklmnopqrst");
        byte[] compact = NbtBinaryCodec.encode(current, 1024);
        assertArrayEquals(new byte[] {'N', 'B', 'T', 1}, java.util.Arrays.copyOf(compact, 4));
        assertEquals(current.getString("value"), NbtBinaryCodec.decode(compact,
                NbtBinaryCodec.Limits.forRawBytes(1024)).getString("value"));

    }

    @Test public void repeatedChunkPaletteStringsUseCompactReferences() throws Exception {
        CompoundTag root = new CompoundTag();
        ListTag sections = new ListTag();
        for (int section = 0; section < 16; section++) {
            CompoundTag entry = new CompoundTag();
            entry.putByte("Y", (byte) section);
            CompoundTag states = new CompoundTag();
            ListTag palette = new ListTag();
            for (int state = 0; state < 8; state++) {
                CompoundTag block = new CompoundTag();
                block.putString("Name", "minecraft:stone");
                CompoundTag properties = new CompoundTag();
                properties.putString("axis", "y");
                block.put("Properties", properties);
                palette.add(block);
            }
            states.put("palette", palette);
            entry.put("block_states", states);
            sections.add(entry);
        }
        root.put("sections", sections);

        byte[] encoded = NbtBinaryCodec.encode(root, 64 * 1024);
        CompoundTag decoded = NbtBinaryCodec.decode(encoded,
                NbtBinaryCodec.Limits.forRawBytes(64 * 1024));
        assertEquals(root, decoded);
        assertTrue("Repeated section and palette strings should be represented by dictionary references",
                encoded.length < 16 * 8 * 55);
    }

    @Test public void rejectsMalformedCompactStringSequences() throws Exception {
        byte[] overlongAscii = {'N', 'B', 'T', 1, 8, 1, 'k', 2, (byte) 0xc0, (byte) 0x80, 0};
        try {
            NbtBinaryCodec.decode(overlongAscii, NbtBinaryCodec.Limits.forRawBytes(128));
            fail("Overlong string encoding must be rejected");
        } catch (IOException expected) { }

        byte[] truncatedSequence = {'N', 'B', 'T', 1, 8, 1, 'k', 1, (byte) 0xc2, 0};
        try {
            NbtBinaryCodec.decode(truncatedSequence, NbtBinaryCodec.Limits.forRawBytes(128));
            fail("Truncated string sequence must be rejected");
        } catch (IOException expected) { }
    }

    @Test public void rejectsTruncatedTrailingOversizedAndOverNestedPayloads() throws Exception {
        CompoundTag root = new CompoundTag();
        root.putInt("value", 7);
        byte[] encoded = NbtBinaryCodec.encode(root, 1024);

        byte[] truncatedBody = java.util.Arrays.copyOf(encoded, encoded.length - 1);
        try { NbtBinaryCodec.decode(truncatedBody, NbtBinaryCodec.Limits.forRawBytes(1024)); fail("Truncation must fail"); }
        catch (IOException expected) { }
        byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        try { NbtBinaryCodec.decode(trailing, NbtBinaryCodec.Limits.forRawBytes(1024)); fail("Trailing bytes must fail"); }
        catch (IOException expected) { }
        try { NbtBinaryCodec.decode(encoded, new NbtBinaryCodec.Limits(2, 8, 8, 8)); fail("Byte limit must be enforced"); }
        catch (IOException expected) { }

        CompoundTag outer = new CompoundTag();
        CompoundTag inner = new CompoundTag();
        inner.putInt("value", 1);
        outer.put("inner", inner);
        byte[] nested = NbtBinaryCodec.encode(outer, 1024);
        try { NbtBinaryCodec.decode(nested, new NbtBinaryCodec.Limits(1024, 1, 32, 512)); fail("Depth limit must be enforced"); }
        catch (IOException expected) { }
    }

    @Test public void rejectsEncodingBeyondItsByteBudget() throws Exception {
        CompoundTag root = new CompoundTag();
        root.putString("value", "a string that cannot fit in the small payload budget");
        try { NbtBinaryCodec.encode(root, 8); fail("Encoder must enforce its byte budget"); }
        catch (IOException expected) { }
    }

    private static ListTag typedEmptyList(int type) throws IOException {
        byte[] encoded = {(byte) type, 0, 0, 0, 0};
        return ListTag.TYPE.load(new DataInputStream(new ByteArrayInputStream(encoded)), NbtAccounter.unlimitedHeap());
    }
}
package pl.fuzjajadrowa.ingot.integration;

import pl.fuzjajadrowa.ingot.Ingot;
import pl.fuzjajadrowa.ingot.compression.CompressionRegistry;
import pl.fuzjajadrowa.ingot.storage.IngotStorage;
import pl.fuzjajadrowa.ingot.storage.StorageOptions;
import pl.fuzjajadrowa.ingot.world.IngotWorldManager;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;

public final class MinecraftIngotStorage implements AutoCloseable {
    private final IngotWorldManager.Session session;
    private final StorageOptions options;
    private final IngotStorage storage;

    public MinecraftIngotStorage(Path vanillaFolder, boolean sync, IngotWorldManager.Session session) {
        this.session = session;
        this.options = StorageOptions.defaults(sync);
        this.storage = new IngotStorage(vanillaFolder.resolve("ingot"), options, CompressionRegistry.defaults(),
                path -> Ingot.LOGGER.warn("Recovered incomplete Ingot append; original tail preserved at {}", path),
                failure -> Ingot.LOGGER.error("Background Ingot compaction failed; the source region remains active", failure));
        session.register(storage);
    }

    public CompoundTag read(ChunkPos pos) {
        session.checkHealthy();
        try {
            byte[] bytes = storage.read(pos.x, pos.z);
            if (bytes == null) return null;
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                CompoundTag tag = NbtIo.read(input, NbtAccounter.create(options.maxRawBytes()));
                if (input.available() != 0) throw new IOException("Trailing NBT bytes at " + pos);
                return tag;
            }
        } catch (IOException | RuntimeException e) { throw session.fail(e); }
    }

    public void scan(ChunkPos pos, StreamTagVisitor visitor) {
        session.checkHealthy();
        try {
            byte[] bytes = storage.read(pos.x, pos.z);
            if (bytes != null) {
                try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                    NbtIo.parse(input, visitor, NbtAccounter.create(options.maxRawBytes()));
                }
            }
        } catch (IOException | RuntimeException e) { throw session.fail(e); }
    }

    public void write(ChunkPos pos, CompoundTag tag) {
        session.checkHealthy();
        try {
            if (tag == null) { storage.write(pos.x, pos.z, null); return; }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(new OutputStream() {
                private int count;
                @Override public void write(int value) throws IOException {
                    reserve(1); bytes.write(value);
                }
                @Override public void write(byte[] data, int offset, int length) throws IOException {
                    reserve(length); bytes.write(data, offset, length);
                }
                private void reserve(int length) throws IOException {
                    if (length < 0 || length > options.maxRawBytes() - count) throw new IOException("NBT exceeds Ingot byte limit");
                    count += length;
                }
            })) { NbtIo.write(tag, output); }
            storage.write(pos.x, pos.z, bytes.toByteArray());
        } catch (IOException | RuntimeException e) { throw session.fail(e); }
    }

    public void flush() {
        session.checkHealthy();
        try { storage.flush(); }
        catch (IOException | RuntimeException e) { throw session.fail(e); }
    }
    @Override public void close() {
        // Never skip shutdown cleanup
        try { storage.close(); }
        catch (IOException | RuntimeException e) { throw session.fail(e); }
        finally { session.unregister(storage); }
    }
    public void checkHealthy() { session.checkHealthy(); }
}
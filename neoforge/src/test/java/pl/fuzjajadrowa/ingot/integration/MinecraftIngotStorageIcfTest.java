package pl.fuzjajadrowa.ingot.integration;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import pl.fuzjajadrowa.ingot.compression.CompressionRegistry;
import pl.fuzjajadrowa.ingot.serialization.IcfCodec;
import pl.fuzjajadrowa.ingot.serialization.IcfPayloadKind;
import pl.fuzjajadrowa.ingot.storage.IngotStorage;
import pl.fuzjajadrowa.ingot.storage.StorageOptions;
import pl.fuzjajadrowa.ingot.world.IngotWorldManager;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class MinecraftIngotStorageIcfTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void readsLegacyNbtAndWritesIcfRecordsInTheSameStorage() throws Exception {
        Path world = temporary.newFolder("world").toPath();
        IngotWorldManager.Session session = IngotWorldManager.openWorld(world);
        Path directory = world.resolve("region").resolve("ingot");
        CompoundTag legacy = new CompoundTag();
        legacy.putString("data", "from the old NBT format");
        legacy.putInt("id", 17);
        CompoundTag fresh = new CompoundTag();
        fresh.putString("data", "new ICF record");
        fresh.putLong("id", 29);

        try {
            try (IngotStorage oldWriter = new IngotStorage(directory, StorageOptions.defaults(false),
                    CompressionRegistry.defaults(), ignored -> { })) {
                oldWriter.write(1, 2, encodeNbt(legacy));
            }

            try (MinecraftIngotStorage storage = new MinecraftIngotStorage(world.resolve("region"), false,
                    session, IcfPayloadKind.CHUNK)) {
                assertEquals(legacy, storage.read(new ChunkPos(1, 2)));
                storage.write(new ChunkPos(3, 4), fresh);
                assertEquals(fresh, storage.read(new ChunkPos(3, 4)));
            }

            try (IngotStorage inspect = new IngotStorage(directory, StorageOptions.defaults(false),
                    CompressionRegistry.defaults(), ignored -> { })) {
                byte[] oldRecord = inspect.read(1, 2);
                byte[] newRecord = inspect.read(3, 4);
                assertFalse(IcfCodec.isIcfPayload(oldRecord));
                assertTrue(IcfCodec.isIcfPayload(newRecord));
                assertEquals(fresh, IcfCodec.decode(newRecord, IcfPayloadKind.CHUNK,
                        pl.fuzjajadrowa.ingot.serialization.NbtBinaryCodec.Limits.forRawBytes(64 * 1024 * 1024)));
            }
        } finally {
            IngotWorldManager.closeWorld(session);
        }
    }

    private static byte[] encodeNbt(CompoundTag tag) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) { NbtIo.write(tag, output); }
        return bytes.toByteArray();
    }
}
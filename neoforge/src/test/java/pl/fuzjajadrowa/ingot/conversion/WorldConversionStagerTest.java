package pl.fuzjajadrowa.ingot.conversion;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import pl.fuzjajadrowa.ingot.world.IngotWorldManifest;
import pl.fuzjajadrowa.ingot.world.WorldStorageDetector;
import pl.fuzjajadrowa.ingot.compression.CompressionRegistry;
import pl.fuzjajadrowa.ingot.serialization.IcfCodec;
import pl.fuzjajadrowa.ingot.serialization.IcfPayloadKind;
import pl.fuzjajadrowa.ingot.storage.IngotStorage;
import pl.fuzjajadrowa.ingot.storage.StorageOptions;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;

public class WorldConversionStagerTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void stagesPublishesAndVerifiesAnvilIngotAnvilRoundTrip() throws Exception {
        Path world = temporary.newFolder("roundtrip").toPath();
        Path regionDir = Files.createDirectories(world.resolve("region"));
        RegionStorageInfo info = new RegionStorageInfo("test", Level.OVERWORLD, "chunk");
        CompoundTag expected = new CompoundTag();
        expected.putString("payload", "preserve this NBT");
        expected.putInt("counter", 17);
        try (RegionFile region = new RegionFile(info, regionDir.resolve("r.-1.2.mca"), regionDir, true);
             DataOutputStream output = region.getChunkDataOutputStream(new ChunkPos(-1, 65))) {
            NbtIo.write(expected, output);
        }

        WorldConversionStager.Result toIngot = WorldConversionStager.stage(world,
                ConversionDirection.ANVIL_TO_INGOT, ignored -> { });
        assertEquals(1L, toIngot.convertedRecords());
        assertTrue(Files.exists(world.resolve("region/r.-1.2.mca")));
        Path ingotRegion = toIngot.stagingDirectory().resolve("region/ingot/r.-1.2.ir");
        assertTrue(Files.exists(ingotRegion));
        WorldConversionStager.PublicationResult firstPublish = WorldConversionStager.publish(world, toIngot, ignored -> { });
        assertEquals(1L, firstPublish.verifiedRecords());
        assertTrue(Files.exists(firstPublish.preservedSource().resolve("region/r.-1.2.mca")));
        assertEquals(WorldStorageDetector.Format.INGOT, WorldStorageDetector.inspect(world).format());
        assertTrue(Files.exists(world.resolve("region/ingot/r.-1.2.ir")));

        WorldConversionStager.Result toAnvil = WorldConversionStager.stage(world,
                ConversionDirection.INGOT_TO_ANVIL, ignored -> { });
        assertEquals(1L, toAnvil.convertedRecords());
        WorldConversionStager.PublicationResult secondPublish = WorldConversionStager.publish(world, toAnvil, ignored -> { });
        assertEquals(1L, secondPublish.verifiedRecords());
        Path returnedRegion = world.resolve("region/r.-1.2.mca");
        try (RegionFile region = new RegionFile(info, returnedRegion, returnedRegion.getParent(), false);
             var input = region.getChunkDataInputStream(new ChunkPos(-1, 65))) {
            assertEquals(expected, NbtIo.read(input));
        }
        assertTrue(Files.exists(secondPublish.preservedSource().resolve("region/ingot/r.-1.2.ir")));
        assertEquals(WorldStorageDetector.Format.ANVIL, WorldStorageDetector.inspect(world).format());
        assertFalse(Files.exists(world.resolve(IngotWorldManifest.FILE_NAME)));
    }

    @Test public void convertsChunksEntitiesAndPoiToTheirMatchingIcfKinds() throws Exception {
        Path world = temporary.newFolder("all-storage-kinds").toPath();
        String[] folders = {"region", "entities", "poi"};
        String[] vanillaTypes = {"chunk", "entities", "poi"};
        IcfPayloadKind[] kinds = {IcfPayloadKind.CHUNK, IcfPayloadKind.ENTITIES, IcfPayloadKind.POI};
        for (int index = 0; index < folders.length; index++) {
            Path directory = Files.createDirectories(world.resolve(folders[index]));
            CompoundTag tag = new CompoundTag();
            tag.putInt("storageKind", index);
            RegionStorageInfo info = new RegionStorageInfo("test", Level.OVERWORLD, vanillaTypes[index]);
            try (RegionFile region = new RegionFile(info, directory.resolve("r.0.0.mca"), directory, true);
                 DataOutputStream output = region.getChunkDataOutputStream(new ChunkPos(index, 2))) {
                NbtIo.write(tag, output);
            }
        }

        WorldConversionStager.Result staged = WorldConversionStager.stage(world,
                ConversionDirection.ANVIL_TO_INGOT, ignored -> { });
        assertEquals(3L, staged.convertedRecords());
        for (int index = 0; index < folders.length; index++) {
            Path directory = staged.stagingDirectory().resolve(folders[index]).resolve("ingot");
            try (IngotStorage storage = new IngotStorage(directory, StorageOptions.defaults(false),
                    CompressionRegistry.defaults(), ignored -> { })) {
                byte[] payload = storage.read(index, 2);
                assertTrue("Expected ICF payload for " + folders[index], IcfCodec.isIcfPayload(payload));
                CompoundTag decoded = IcfCodec.decode(payload, kinds[index],
                        pl.fuzjajadrowa.ingot.serialization.NbtBinaryCodec.Limits.forRawBytes(64 * 1024 * 1024));
                assertEquals(index, decoded.getInt("storageKind"));
            }
        }
    }

    @Test public void failedPublicationRollsBackToOriginalAnvilFiles() throws Exception {
        Path world = temporary.newFolder("rollback").toPath();
        Path regionDir = Files.createDirectories(world.resolve("region"));
        RegionStorageInfo info = new RegionStorageInfo("test", Level.OVERWORLD, "chunk");
        CompoundTag tag = new CompoundTag();
        tag.putInt("value", 9);
        try (RegionFile region = new RegionFile(info, regionDir.resolve("r.0.0.mca"), regionDir, true);
             DataOutputStream output = region.getChunkDataOutputStream(new ChunkPos(2, 3))) {
            NbtIo.write(tag, output);
        }
        WorldConversionStager.Result staged = WorldConversionStager.stage(world, ConversionDirection.ANVIL_TO_INGOT, ignored -> { });
        try {
            WorldConversionStager.publish(world, staged, progress -> {
                if (progress.stage() == ConversionProgress.Stage.PUBLICATION && progress.completed() == 1) {
                    throw new IllegalStateException("Injected publication interruption");
                }
            });
            fail("Injected publication error must be reported");
        } catch (IllegalStateException expected) { }
        assertEquals(WorldStorageDetector.Format.ANVIL, WorldStorageDetector.inspect(world).format());
        assertTrue(Files.exists(world.resolve("region/r.0.0.mca")));
        assertFalse(Files.exists(world.resolve(IngotWorldManifest.FILE_NAME)));
        assertFalse(Files.exists(world.resolve("ingot-conversion.journal")));
    }

    @Test public void recoveryRollsBackPublicationInterruptedAfterArchivingSource() throws Exception {
        Path world = temporary.newFolder("interrupted-publication").toPath();
        Path regionDir = Files.createDirectories(world.resolve("region"));
        RegionStorageInfo info = new RegionStorageInfo("test", Level.OVERWORLD, "chunk");
        CompoundTag tag = new CompoundTag();
        tag.putString("safe", "original");
        try (RegionFile region = new RegionFile(info, regionDir.resolve("r.0.0.mca"), regionDir, true);
             DataOutputStream output = region.getChunkDataOutputStream(new ChunkPos(1, 1))) {
            NbtIo.write(tag, output);
        }
        WorldConversionStager.Result staged = WorldConversionStager.stage(world, ConversionDirection.ANVIL_TO_INGOT, ignored -> { });
        try {
            WorldConversionStager.publish(world, staged, progress -> {
                if (progress.stage() == ConversionProgress.Stage.PUBLICATION && progress.completed() == 1) {
                    throw new AssertionError("Simulated process termination");
                }
            });
            fail("Simulated interruption must escape the publisher");
        } catch (AssertionError expected) { }
        assertTrue(Files.exists(world.resolve(ConversionJournal.FILE_NAME)));
        assertFalse(Files.exists(world.resolve("region/r.0.0.mca")));

        WorldConversionStager.recover(world);
        assertEquals(WorldStorageDetector.Format.ANVIL, WorldStorageDetector.inspect(world).format());
        assertTrue(Files.exists(world.resolve("region/r.0.0.mca")));
        assertFalse(Files.exists(world.resolve(ConversionJournal.FILE_NAME)));
    }
}
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

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WorldConversionStagerTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void stagesAndVerifiesAnvilIngotAnvilWithoutPublishing() throws Exception {
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

        Path activeIngot = Files.createDirectories(world.resolve("region/ingot"));
        Files.copy(ingotRegion, activeIngot.resolve(ingotRegion.getFileName()));
        Files.delete(world.resolve("region/r.-1.2.mca"));
        new IngotWorldManifest(UUID.randomUUID()).write(world);

        WorldConversionStager.Result toAnvil = WorldConversionStager.stage(world,
                ConversionDirection.INGOT_TO_ANVIL, ignored -> { });
        assertEquals(1L, toAnvil.convertedRecords());
        Path returnedRegion = toAnvil.stagingDirectory().resolve("region/r.-1.2.mca");
        try (RegionFile region = new RegionFile(info, returnedRegion, returnedRegion.getParent(), false);
             var input = region.getChunkDataInputStream(new ChunkPos(-1, 65))) {
            assertEquals(expected, NbtIo.read(input));
        }
        assertTrue(Files.exists(world.resolve("region/ingot/r.-1.2.ir")));
        assertTrue(Files.exists(world.resolve(IngotWorldManifest.FILE_NAME)));
    }
}
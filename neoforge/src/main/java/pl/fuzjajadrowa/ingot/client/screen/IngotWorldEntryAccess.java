package pl.fuzjajadrowa.ingot.client.screen;

import net.minecraft.world.level.storage.LevelSummary;
import pl.fuzjajadrowa.ingot.world.WorldStorageDetector;

public interface IngotWorldEntryAccess {
    LevelSummary ingot$summary();
    WorldStorageDetector.Detection ingot$storageDetection();
}
package pl.fuzjajadrowa.ingot.client.screen;

import pl.fuzjajadrowa.ingot.world.WorldStorageDetector;

public interface IngotCreateWorldAccess {
    WorldStorageDetector.Format ingot$getStorageFormat();

    void ingot$setStorageFormat(WorldStorageDetector.Format format);
}
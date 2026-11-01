package pl.fuzjajadrowa.ingot.conversion;

import pl.fuzjajadrowa.ingot.world.WorldStorageDetector;

public enum ConversionDirection {
    ANVIL_TO_INGOT,
    INGOT_TO_ANVIL;

    public WorldStorageDetector.Format sourceFormat() {
        return this == ANVIL_TO_INGOT ? WorldStorageDetector.Format.ANVIL : WorldStorageDetector.Format.INGOT;
    }

    public WorldStorageDetector.Format targetFormat() {
        return this == ANVIL_TO_INGOT ? WorldStorageDetector.Format.INGOT : WorldStorageDetector.Format.ANVIL;
    }
}
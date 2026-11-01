package pl.fuzjajadrowa.ingot.conversion;

import pl.fuzjajadrowa.ingot.world.IngotWorldManager;
import pl.fuzjajadrowa.ingot.world.WorldStorageDetector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

public final class WorldConversionPreflight {
    private WorldConversionPreflight() { }

    public static WorldConversionLock validateAndLock(Path directory, ConversionDirection direction) throws IOException {
        Path requested = directory.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(requested)) throw new IOException("World path is a symbolic link: " + requested);
        Path root = directory.toRealPath();
        WorldConversionLock lock = IngotWorldManager.acquireConversionLock(root);
        boolean accepted = false;
        try {
            if (Files.exists(root.resolve(ConversionJournal.FILE_NAME), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("An interrupted conversion journal must be recovered before another attempt: " + root);
            }
            WorldStorageDetector.Detection detection = WorldStorageDetector.inspect(root);
            if (detection.format() != direction.sourceFormat()) {
                throw new IOException("Conversion expects " + direction.sourceFormat() + " storage but found " + detection.format());
            }
            accepted = true;
            return lock;
        } finally {
            if (!accepted) lock.close();
        }
    }
}
package pl.fuzjajadrowa.ingot.conversion;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class WorldConversionLock implements AutoCloseable {
    private final FileChannel channel;
    private final FileLock lock;

    private WorldConversionLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static WorldConversionLock acquire(Path root) throws IOException {
        Path world = root.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(world) || !Files.isDirectory(world, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World root is missing or unsafe: " + world);
        }
        Path file = world.resolve(".ingot-conversion.lock");
        if (Files.isSymbolicLink(file)) throw new IOException("Conversion lock path is a symbolic link: " + file);
        FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("World is already being converted: " + world);
            return new WorldConversionLock(channel, lock);
        } catch (OverlappingFileLockException busy) {
            channel.close();
            throw new IOException("World is already being converted: " + world, busy);
        } catch (IOException failure) {
            channel.close();
            throw failure;
        }
    }

    public static boolean isLocked(Path root) throws IOException {
        Path world = root.toAbsolutePath().normalize();
        Path file = world.resolve(".ingot-conversion.lock");
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return false;
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Conversion lock path is unsafe: " + file);
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            try {
                FileLock probe = channel.tryLock();
                if (probe == null) return true;
                probe.release();
                return false;
            } catch (OverlappingFileLockException busy) {
                return true;
            }
        }
    }

    @Override public void close() throws IOException {
        try { lock.release(); }
        finally { channel.close(); }
    }
}
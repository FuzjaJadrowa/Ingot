package pl.fuzjajadrowa.ingot.conversion;

import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import pl.fuzjajadrowa.ingot.compression.CompressionRegistry;
import pl.fuzjajadrowa.ingot.storage.IngotStorage;
import pl.fuzjajadrowa.ingot.storage.StorageOptions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public final class WorldConversionStager {
    private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.(mca|ir)");
    private static final Pattern EXTERNAL_CHUNK = Pattern.compile("c\\.(-?\\d+)\\.(-?\\d+)\\.mcc");
    private static final List<String> TYPES = List.of("region", "entities", "poi");

    public record Result(Path stagingDirectory, ConversionDirection direction, long convertedRecords) { }
    private record StorageFolder(Path relative, String type, ResourceKey<Level> dimension) { }
    private record Region(Path source, int x, int z) { }

    private WorldConversionStager() { }

    public static Result stage(Path world, ConversionDirection direction, Consumer<ConversionProgress> progress)
            throws IOException {
        Consumer<ConversionProgress> listener = progress == null ? ignored -> { } : progress;
        Path root = world.toAbsolutePath().normalize();
        try (WorldConversionLock ignored = WorldConversionPreflight.validateAndLock(root, direction)) {
            ConversionJournal journal = ConversionJournal.create(root, direction);
            Path staging = root.resolve(".ingot-conversion-stage-" + journal.id());
            try {
                listener.accept(new ConversionProgress(ConversionProgress.Stage.PREFLIGHT, "Scanning region files", 0, 0));
                List<StorageFolder> folders = discoverStorageFolders(root);
                List<Region> regions = discoverRegions(root, folders, direction);
                Files.createDirectory(staging);
                journal = journal.advance(root, ConversionJournal.Phase.STAGING, "staging=" + staging.getFileName());
                long converted = 0;
                long done = 0;
                for (StorageFolder folder : folders) {
                    List<Region> inFolder = regions.stream().filter(r -> r.source().getParent().equals(sourceDirectory(root, folder, direction)))
                            .sorted(Comparator.comparing(r -> r.source().getFileName().toString())).toList();
                    for (Region region : inFolder) {
                        Path output = targetDirectory(staging, folder, direction);
                        Files.createDirectories(output);
                        listener.accept(new ConversionProgress(ConversionProgress.Stage.STAGING,
                                "Converting " + folder.type() + " region " + region.x() + "," + region.z(), ++done, regions.size()));
                        converted += direction == ConversionDirection.ANVIL_TO_INGOT
                                ? copyAnvilToIngot(region, output, folder)
                                : copyIngotToAnvil(region, output, folder);
                    }
                }

                journal = journal.advance(root, ConversionJournal.Phase.VERIFICATION, "staged records=" + converted);
                listener.accept(new ConversionProgress(ConversionProgress.Stage.VERIFICATION, "Verifying staged regions", 0, regions.size()));
                long verified = verify(root, staging, folders, regions, direction, listener);
                if (verified != converted) throw new IOException("Staging verification count mismatch: wrote " + converted + ", read " + verified);

                // This stage intentionally stops before publication
                journal = journal.advance(root, ConversionJournal.Phase.STAGED,
                        "verified staging=" + staging.getFileName() + "; publication not performed");
                journal.clearAfterSuccessfulCompletion(root);
                listener.accept(new ConversionProgress(ConversionProgress.Stage.COMPLETE, "Staging verified; source unchanged", regions.size(), regions.size()));
                return new Result(staging, direction, converted);
            } catch (Exception failure) {
                try {
                    ConversionJournal current = ConversionJournal.read(root);
                    if (current.phase() != ConversionJournal.Phase.COMPLETE && current.phase() != ConversionJournal.Phase.FAILED) {
                        current.advance(root, ConversionJournal.Phase.FAILED, failure.toString());
                    }
                } catch (Throwable journalFailure) { failure.addSuppressed(journalFailure); }
                if (failure instanceof IOException io) throw io;
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new IOException("World conversion staging failed", failure);
            }
        }
    }

    private static List<StorageFolder> discoverStorageFolders(Path root) throws IOException {
        List<StorageFolder> result = new ArrayList<>();
        addDimensionFolders(root, Path.of(""), ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld")), result);
        addDimensionFolders(root.resolve("DIM-1"), Path.of("DIM-1"), Level.NETHER, result);
        addDimensionFolders(root.resolve("DIM1"), Path.of("DIM1"), Level.END, result);
        Path dimensions = root.resolve("dimensions");
        if (Files.isSymbolicLink(dimensions)) throw new IOException("Linked mod dimensions directory: " + dimensions);
        if (Files.isDirectory(dimensions, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> paths = Files.walk(dimensions)) {
                for (Path dir : paths.filter(p -> Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                    Path relative = dimensions.relativize(dir);
                    if (relative.getNameCount() >= 3 && TYPES.contains(dir.getFileName().toString())) {
                        Path dimensionPath = relative.subpath(0, relative.getNameCount() - 1);
                        String id = dimensionPath.getName(0) + ":" + dimensionPath.subpath(1, dimensionPath.getNameCount()).toString().replace('\\', '/');
                        ResourceLocation location = ResourceLocation.tryParse(id);
                        if (location == null) throw new IOException("Invalid dimension identifier in directory: " + dir);
                        addFolder(dir, root.relativize(dir), dir.getFileName().toString(), ResourceKey.create(Registries.DIMENSION, location), result);
                    }
                }
            }
        }
        return result;
    }

    private static void addDimensionFolders(Path dimensionRoot, Path relativeRoot, ResourceKey<Level> key,
                                            List<StorageFolder> result) throws IOException {
        if (Files.isSymbolicLink(dimensionRoot)) throw new IOException("Linked dimension directory: " + dimensionRoot);
        if (!Files.isDirectory(dimensionRoot, LinkOption.NOFOLLOW_LINKS)) return;
        for (String type : TYPES) {
            Path path = dimensionRoot.resolve(type);
            if (Files.isSymbolicLink(path)) throw new IOException("Linked storage directory: " + path);
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) addFolder(path, relativeRoot.resolve(type), type, key, result);
        }
    }

    private static void addFolder(Path path, Path relative, String type, ResourceKey<Level> key,
                                  List<StorageFolder> result) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Linked storage directory: " + path);
        result.add(new StorageFolder(relative, type, key));
    }

    private static List<Region> discoverRegions(Path root, List<StorageFolder> folders, ConversionDirection direction) throws IOException {
        List<Region> result = new ArrayList<>();
        for (StorageFolder folder : folders) {
            Path source = sourceDirectory(root, folder, direction);
            if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) continue;
            if (Files.isSymbolicLink(source)) throw new IOException("Linked source storage directory: " + source);
            try (Stream<Path> children = Files.list(source)) {
                for (Path file : children.toList()) {
                    Matcher matcher = REGION_NAME.matcher(file.getFileName().toString());
                    String extension = direction == ConversionDirection.ANVIL_TO_INGOT ? "mca" : "ir";
                    if (!matcher.matches()) {
                        if (direction == ConversionDirection.ANVIL_TO_INGOT) validateExternalChunkArtifact(file);
                        continue;
                    }
                    if (!matcher.group(3).equals(extension)) continue;
                    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Unsafe region file: " + file);
                    }
                    int regionX = Integer.parseInt(matcher.group(1));
                    int regionZ = Integer.parseInt(matcher.group(2));
                    validateRegionCoordinate(regionX, file);
                    validateRegionCoordinate(regionZ, file);
                    result.add(new Region(file, regionX, regionZ));
                }
            }
        }
        return result;
    }

    private static void validateExternalChunkArtifact(Path file) throws IOException {
        Matcher matcher = EXTERNAL_CHUNK.matcher(file.getFileName().toString());
        if (!matcher.matches()) return;
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Unsafe external Anvil chunk file: " + file);
        }
        int chunkX = Integer.parseInt(matcher.group(1));
        int chunkZ = Integer.parseInt(matcher.group(2));
        Path region = file.getParent().resolve("r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
        if (!Files.isRegularFile(region, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("External Anvil chunk has no parent region file: " + file);
        }
    }

    private static void validateRegionCoordinate(int coordinate, Path file) throws IOException {
        if (coordinate < Integer.MIN_VALUE / 32 || coordinate > Integer.MAX_VALUE / 32) {
            throw new IOException("Region coordinate exceeds Minecraft chunk coordinate range: " + file);
        }
    }

    private static Path sourceDirectory(Path root, StorageFolder folder, ConversionDirection direction) {
        Path path = root.resolve(folder.relative());
        return direction == ConversionDirection.ANVIL_TO_INGOT ? path : path.resolve("ingot");
    }

    private static Path targetDirectory(Path staging, StorageFolder folder, ConversionDirection direction) {
        Path path = staging.resolve(folder.relative());
        return direction == ConversionDirection.ANVIL_TO_INGOT ? path.resolve("ingot") : path;
    }

    private static long copyAnvilToIngot(Region source, Path target, StorageFolder folder) throws IOException {
        long records = 0;
        RegionStorageInfo info = info(folder);
        try (RegionFile input = new RegionFile(info, source.source(), source.source().getParent(), false);
             IngotStorage output = new IngotStorage(target, StorageOptions.defaults(true), CompressionRegistry.defaults(), ignored -> { })) {
            for (int dz = 0; dz < 32; dz++) for (int dx = 0; dx < 32; dx++) {
                ChunkPos pos = new ChunkPos((source.x() << 5) + dx, (source.z() << 5) + dz);
                try (DataInputStream data = input.getChunkDataInputStream(pos)) {
                    if (data == null) continue;
                    CompoundTag tag = NbtIo.read(data, NbtAccounter.create(64L * 1024 * 1024));
                    output.write(pos.x, pos.z, encode(tag));
                    records++;
                }
            }
            output.flush();
        }
        return records;
    }

    private static long copyIngotToAnvil(Region source, Path target, StorageFolder folder) throws IOException {
        long records = 0;
        Files.createDirectories(target);
        RegionStorageInfo info = info(folder);
        Path targetFile = target.resolve("r." + source.x() + "." + source.z() + ".mca");
        try (IngotStorage input = new IngotStorage(source.source().getParent(), StorageOptions.defaults(false), CompressionRegistry.defaults(), ignored -> { });
             RegionFile output = new RegionFile(info, targetFile, target, true)) {
            for (int dz = 0; dz < 32; dz++) for (int dx = 0; dx < 32; dx++) {
                int x = (source.x() << 5) + dx, z = (source.z() << 5) + dz;
                byte[] bytes = input.read(x, z);
                if (bytes == null) continue;
                CompoundTag tag = decode(bytes);
                try (DataOutputStream data = output.getChunkDataOutputStream(new ChunkPos(x, z))) {
                    NbtIo.write(tag, data);
                }
                records++;
            }
            output.flush();
        }
        return records;
    }

    private static long verify(Path root, Path staging, List<StorageFolder> folders, List<Region> regions,
                               ConversionDirection direction, Consumer<ConversionProgress> listener) throws IOException {
        long count = 0;
        long done = 0;
        for (Region region : regions) {
            StorageFolder folder = folders.stream().filter(f -> sourceDirectory(root, f, direction).equals(region.source().getParent())).findFirst()
                    .orElseThrow(() -> new IOException("Lost storage folder for " + region.source()));
            Path target = targetDirectory(staging, folder, direction);
            listener.accept(new ConversionProgress(ConversionProgress.Stage.VERIFICATION,
                    "Checking " + folder.type() + " region " + region.x() + "," + region.z(), ++done, regions.size()));
            if (direction == ConversionDirection.ANVIL_TO_INGOT) {
                count += verifyAnvilToIngot(region, target, folder);
            } else {
                count += verifyIngotToAnvil(region, target, folder);
            }
        }
        return count;
    }

    private static long verifyAnvilToIngot(Region source, Path target, StorageFolder folder) throws IOException {
        long count = 0;
        try (RegionFile input = new RegionFile(info(folder), source.source(), source.source().getParent(), false);
             IngotStorage output = new IngotStorage(target, StorageOptions.defaults(false), CompressionRegistry.defaults(), ignored -> { })) {
            for (int dz = 0; dz < 32; dz++) for (int dx = 0; dx < 32; dx++) {
                int x = (source.x() << 5) + dx, z = (source.z() << 5) + dz;
                CompoundTag expected;
                try (DataInputStream data = input.getChunkDataInputStream(new ChunkPos(x, z))) {
                    if (data == null) {
                        if (output.contains(x, z)) throw new IOException("Unexpected staged Ingot record at " + x + "," + z);
                        continue;
                    }
                    expected = NbtIo.read(data, NbtAccounter.create(64L * 1024 * 1024));
                }
                byte[] bytes = output.read(x, z);
                if (bytes == null || !expected.equals(decode(bytes))) throw new IOException("Staged Ingot data mismatch at " + x + "," + z);
                count++;
            }
        }
        return count;
    }

    private static long verifyIngotToAnvil(Region source, Path target, StorageFolder folder) throws IOException {
        long count = 0;
        Path targetFile = target.resolve("r." + source.x() + "." + source.z() + ".mca");
        try (IngotStorage input = new IngotStorage(source.source().getParent(), StorageOptions.defaults(false), CompressionRegistry.defaults(), ignored -> { });
             RegionFile output = new RegionFile(info(folder), targetFile, target, false)) {
            for (int dz = 0; dz < 32; dz++) for (int dx = 0; dx < 32; dx++) {
                int x = (source.x() << 5) + dx, z = (source.z() << 5) + dz;
                byte[] bytes = input.read(x, z);
                if (bytes == null) {
                    if (output.doesChunkExist(new ChunkPos(x, z))) throw new IOException("Unexpected staged Anvil chunk at " + x + "," + z);
                    continue;
                }
                CompoundTag expected = decode(bytes);
                try (DataInputStream data = output.getChunkDataInputStream(new ChunkPos(x, z))) {
                    if (data == null || !expected.equals(NbtIo.read(data, NbtAccounter.create(64L * 1024 * 1024)))) {
                        throw new IOException("Staged Anvil data mismatch at " + x + "," + z);
                    }
                }
                count++;
            }
        }
        return count;
    }

    private static RegionStorageInfo info(StorageFolder folder) {
        return new RegionStorageInfo("IngotConversion", folder.dimension(), folder.type());
    }

    private static byte[] encode(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) { NbtIo.write(tag, out); }
        return bytes.toByteArray();
    }

    private static CompoundTag decode(byte[] bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
            CompoundTag result = NbtIo.read(input, NbtAccounter.create(64L * 1024 * 1024));
            if (input.available() != 0) throw new IOException("Trailing bytes after NBT compound");
            return result;
        }
    }
}
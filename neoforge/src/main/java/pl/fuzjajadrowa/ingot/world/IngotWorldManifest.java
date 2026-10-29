package pl.fuzjajadrowa.ingot.world;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.CRC32C;

public record IngotWorldManifest(UUID worldId) {
    public static final String FILE_NAME = "ingot-manifest";
    private static final int MAX_BYTES = 4096;
    private static final Pattern VERSION = Pattern.compile("[0-9A-Za-z][0-9A-Za-z.+_-]{0,127}");
    private static final String IMPLEMENTATION_VERSION = readImplementationVersion();

    private static String readImplementationVersion() {
        try (var input = IngotWorldManifest.class.getResourceAsStream("/ingot-version.properties")) {
            if (input == null) throw new IllegalStateException("Missing generated Ingot version metadata");
            var properties = new java.util.Properties();
            properties.load(input);
            String version = properties.getProperty("implementationVersion");
            if (version == null || !VERSION.matcher(version).matches() || version.contains("${")) {
                throw new IllegalStateException("Invalid generated Ingot version metadata");
            }
            return version;
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private String body() {
        return "format=ingot-region\nformatVersion=1\nimplementationVersion=" + IMPLEMENTATION_VERSION + "\n"
                + "storageMode=authoritative\nminecraft=1.21.1\nregionWidth=32\ncodec=1\nworldId=" + worldId + "\n";
    }

    public void write(Path world) throws IOException {
        String body = body();
        byte[] data = (body + "crc32c=" + checksum(body) + "\n").getBytes(StandardCharsets.UTF_8);
        Path temporary = world.resolve(FILE_NAME + ".pending");
        Path target = world.resolve(FILE_NAME);
        if (!Files.isDirectory(world, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World root is not a directory: " + world);
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Refusing to overwrite existing Ingot manifest: " + target);
        }

        try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = ByteBuffer.wrap(data);
            while (bytes.hasRemaining()) output.write(bytes);
            output.force(true);
        }

        Files.createLink(target, temporary);
        Files.delete(temporary);
        if (!read(world).equals(this)) throw new IOException("Manifest publication verification failed: " + target);
    }

    public static IngotWorldManifest read(Path world) throws IOException {
        Path file = world.resolve(FILE_NAME);
        if (Files.isSymbolicLink(file)) throw new IOException("Refusing to read a linked Ingot manifest: " + file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Ingot manifest is missing or is not a regular file: " + file);
        }
        long size = Files.size(file);
        if (size > MAX_BYTES) throw new IOException("Ingot manifest exceeds size limit: " + file);

        String content = Files.readString(file, StandardCharsets.UTF_8);
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IOException("Ingot manifest exceeds size limit: " + file);
        }
        int split = content.indexOf("crc32c=");
        if (split < 0) throw new IOException("Missing manifest checksum: " + file);
        String body = content.substring(0, split);
        if (!content.substring(split).equals("crc32c=" + checksum(body) + "\n")) {
            throw new IOException("Manifest checksum mismatch: " + file);
        }

        String[] lines = body.split("\n");
        if (lines.length != 8 || !lines[0].equals("format=ingot-region") || !lines[1].equals("formatVersion=1")
                || !lines[2].startsWith("implementationVersion=")
                || !VERSION.matcher(lines[2].substring("implementationVersion=".length())).matches()
                || !lines[3].equals("storageMode=authoritative") || !lines[4].equals("minecraft=1.21.1")
                || !lines[5].equals("regionWidth=32") || !lines[6].equals("codec=1") || !lines[7].startsWith("worldId=")) {
            throw new IOException("Unsupported Ingot manifest: " + file);
        }
        try {
            return new IngotWorldManifest(UUID.fromString(lines[7].substring("worldId=".length())));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid manifest world ID: " + file, e);
        }
    }

    private static String checksum(String body) {
        CRC32C crc = new CRC32C();
        crc.update(body.getBytes(StandardCharsets.UTF_8));
        return String.format(java.util.Locale.ROOT, "%08x", crc.getValue());
    }
}
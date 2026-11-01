package pl.fuzjajadrowa.ingot.conversion;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.Base64;
import java.util.UUID;
import java.util.zip.CRC32C;

public record ConversionJournal(UUID id, ConversionDirection direction, Phase phase, String detail) {
    public static final String FILE_NAME = "ingot-conversion.journal";
    private static final String TEMP_NAME = FILE_NAME + ".pending";

    public enum Phase { PREFLIGHT, STAGING, VERIFICATION, STAGED, PUBLICATION, COMPLETE, FAILED }

    public ConversionJournal {
        if (id == null || direction == null || phase == null || detail == null || detail.length() > 1024) {
            throw new IllegalArgumentException("Invalid conversion journal fields");
        }
    }

    public static ConversionJournal create(Path root, ConversionDirection direction) throws IOException {
        ConversionJournal journal = new ConversionJournal(UUID.randomUUID(), direction, Phase.PREFLIGHT, "");
        Path path = checkedRoot(root).resolve(FILE_NAME);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("A conversion journal already exists: " + path);
        journal.writeNew(path);
        return journal;
    }

    public static ConversionJournal read(Path root) throws IOException {
        Path file = checkedRoot(root).resolve(FILE_NAME);
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Conversion journal is missing or unsafe: " + file);
        }
        if (Files.size(file) > 8192) throw new IOException("Conversion journal exceeds size limit: " + file);
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length > 8192) throw new IOException("Conversion journal exceeds size limit: " + file);
        String content = new String(bytes, StandardCharsets.UTF_8);
        int marker = content.lastIndexOf("crc32c=");
        if (marker < 0) throw new IOException("Conversion journal checksum is missing: " + file);
        String body = content.substring(0, marker);
        if (!content.substring(marker).equals("crc32c=" + checksum(body) + "\n")) {
            throw new IOException("Conversion journal checksum mismatch: " + file);
        }
        Properties p = new Properties();
        p.load(new java.io.StringReader(body));
        try {
            if (!"1".equals(p.getProperty("version"))) throw new IllegalArgumentException("unsupported version");
            return new ConversionJournal(UUID.fromString(p.getProperty("id")),
                    ConversionDirection.valueOf(p.getProperty("direction")), Phase.valueOf(p.getProperty("phase")),
                    new String(Base64.getDecoder().decode(p.getProperty("detail64", "")), StandardCharsets.UTF_8));
        } catch (RuntimeException malformed) {
            throw new IOException("Invalid conversion journal: " + file, malformed);
        }
    }

    public ConversionJournal advance(Path root, Phase next, String note) throws IOException {
        if (!isAllowedTransition(phase, next)) throw new IOException("Invalid conversion journal phase transition " + phase + " -> " + next);
        ConversionJournal updated = new ConversionJournal(id, direction, next, note);
        updated.replace(checkedRoot(root).resolve(FILE_NAME));
        return updated;
    }

    public void clearAfterSuccessfulCompletion(Path root) throws IOException {
        if (phase != Phase.STAGED && phase != Phase.COMPLETE) throw new IOException("Cannot remove an incomplete conversion journal");
        Path file = checkedRoot(root).resolve(FILE_NAME);
        ConversionJournal current = read(root);
        if (!current.equals(this)) throw new IOException("Conversion journal changed; refusing to remove it: " + file);
        Files.delete(file);
    }

    private void writeNew(Path target) throws IOException {
        Path temp = target.resolveSibling(TEMP_NAME);
        byte[] bytes = serialize().getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writeFully(channel, bytes);
            channel.force(true);
        }
        try { Files.createLink(target, temp); }
        catch (IOException failure) {
            Files.deleteIfExists(temp);
            throw failure;
        }
        Files.delete(temp);
    }

    private void replace(Path target) throws IOException {
        Path temp = target.resolveSibling(TEMP_NAME);
        byte[] bytes = serialize().getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            writeFully(channel, bytes);
            channel.force(true);
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.deleteIfExists(temp);
            throw new IOException("Filesystem does not support atomic conversion journal updates", unsupported);
        } catch (IOException failure) {
            Files.deleteIfExists(temp);
            throw failure;
        }
    }

    private String serialize() {
        String body = "version=1\nid=" + id + "\ndirection=" + direction.name() + "\nphase=" + phase.name()
                + "\ndetail64=" + Base64.getEncoder().encodeToString(detail.getBytes(StandardCharsets.UTF_8)) + "\n";
        return body + "crc32c=" + checksum(body) + "\n";
    }

    private static String checksum(String value) {
        CRC32C crc = new CRC32C();
        crc.update(value.getBytes(StandardCharsets.UTF_8));
        return String.format(java.util.Locale.ROOT, "%08x", crc.getValue());
    }

    private static void writeFully(FileChannel channel, byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while (buffer.hasRemaining()) channel.write(buffer);
    }

    private static Path checkedRoot(Path root) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(absolute) || !Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World root is missing or unsafe: " + absolute);
        }
        return absolute;
    }

    private static boolean isAllowedTransition(Phase from, Phase to) {
        return switch (from) {
            case PREFLIGHT -> to == Phase.STAGING || to == Phase.FAILED;
            case STAGING -> to == Phase.VERIFICATION || to == Phase.FAILED;
            case VERIFICATION -> to == Phase.STAGED || to == Phase.PUBLICATION || to == Phase.FAILED;
            case STAGED -> false;
            case PUBLICATION -> to == Phase.COMPLETE || to == Phase.FAILED;
            case COMPLETE, FAILED -> false;
        };
    }
}
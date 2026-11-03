package pl.fuzjajadrowa.ingot.benchmark;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import pl.fuzjajadrowa.ingot.compression.ZstdCodec;
import pl.fuzjajadrowa.ingot.serialization.IcfCodec;
import pl.fuzjajadrowa.ingot.serialization.IcfPayloadKind;
import pl.fuzjajadrowa.ingot.storage.IngotRegionFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public final class WorldStorageBenchmark {
    private static final int SECTOR_BYTES = 4096;
    private static final int ANVIL_HEADER_BYTES = 8192;
    private static final int MAX_RAW_BYTES = 64 * 1024 * 1024;
    private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
    private static final ZstdCodec ZSTD = new ZstdCodec(3);
    private static final ZstdCodec ZSTD_6 = new ZstdCodec(6);
    private static final ZstdCodec ZSTD_9 = new ZstdCodec(9);

    private record RegionName(int x, int z) { }
    private record SourceRecord(byte[] raw) { }

    private static final class Totals {
        long rawNbt;
        long rawIcf;
        long nbtZlib;
        long nbtZstd;
        long nbtZstd6;
        long nbtZstd9;
        long icfZstd;
        long icfZstd6;
        long icfZstd9;
        long icfZlib;
        long count;
        long anvilBytes;
        long nbtIngotBytes;
        long nbtZstd9IngotBytes;
        long nbtZstd6IngotBytes;
        long icfIngotBytes;
        long icfZstd6IngotBytes;
        long icfZstd9IngotBytes;
        long icfZlibIngotBytes;
        long zlibIngotBytes;
        void add(Totals other) {
            rawNbt += other.rawNbt; rawIcf += other.rawIcf; nbtZlib += other.nbtZlib;
            nbtZstd += other.nbtZstd; nbtZstd6 += other.nbtZstd6; nbtZstd9 += other.nbtZstd9;
            icfZstd += other.icfZstd; icfZstd6 += other.icfZstd6; icfZstd9 += other.icfZstd9;
            icfZlib += other.icfZlib; count += other.count;
            anvilBytes += other.anvilBytes; nbtIngotBytes += other.nbtIngotBytes;
            nbtZstd9IngotBytes += other.nbtZstd9IngotBytes; nbtZstd6IngotBytes += other.nbtZstd6IngotBytes;
            icfIngotBytes += other.icfIngotBytes; icfZstd6IngotBytes += other.icfZstd6IngotBytes;
            icfZstd9IngotBytes += other.icfZstd9IngotBytes;
            icfZlibIngotBytes += other.icfZlibIngotBytes;
            zlibIngotBytes += other.zlibIngotBytes;
        }

        void addRecord(byte[] raw, byte[] icf, long regionNbtZlib, long regionNbtZstd,
                       long regionNbtZstd6, long regionNbtZstd9, long regionIcfZlib,
                       long regionIcfZstd, long regionIcfZstd6, long regionIcfZstd9) {
            rawNbt += raw.length; rawIcf += icf.length;
            nbtZlib += regionNbtZlib; nbtZstd += regionNbtZstd;
            nbtZstd6 += regionNbtZstd6; nbtZstd9 += regionNbtZstd9;
            icfZlib += regionIcfZlib; icfZstd += regionIcfZstd;
            icfZstd6 += regionIcfZstd6; icfZstd9 += regionIcfZstd9;
            count++;
            nbtIngotBytes += IngotRegionFile.RECORD_HEADER_SIZE + IngotRegionFile.COMMIT_SIZE + regionNbtZstd;
            nbtZstd6IngotBytes += IngotRegionFile.RECORD_HEADER_SIZE + IngotRegionFile.COMMIT_SIZE + regionNbtZstd6;
            nbtZstd9IngotBytes += IngotRegionFile.RECORD_HEADER_SIZE + IngotRegionFile.COMMIT_SIZE + regionNbtZstd9;
            icfIngotBytes += IngotRegionFile.RECORD_HEADER_SIZE + IngotRegionFile.COMMIT_SIZE + regionIcfZstd;
            icfZstd6IngotBytes += IngotRegionFile.RECORD_HEADER_SIZE + IngotRegionFile.COMMIT_SIZE + regionIcfZstd6;
            icfZstd9IngotBytes += IngotRegionFile.RECORD_HEADER_SIZE + IngotRegionFile.COMMIT_SIZE + regionIcfZstd9;
            icfZlibIngotBytes += IngotRegionFile.RECORD_HEADER_SIZE + IngotRegionFile.COMMIT_SIZE + regionIcfZlib;
            zlibIngotBytes += IngotRegionFile.RECORD_HEADER_SIZE + IngotRegionFile.COMMIT_SIZE + regionNbtZlib;
        }
    }

    private WorldStorageBenchmark() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException(
                "Usage: WorldStorageBenchmark <world-directory> <report-directory>");
        Path world = Path.of(args[0]).toAbsolutePath().normalize();
        Path report = Path.of(args[1]);
        if (!Files.isDirectory(world) || !Files.isRegularFile(world.resolve("level.dat"))) {
            throw new IOException("Not a Minecraft world directory: " + world);
        }
        WorldResult result = measureWorld(world);
        Files.createDirectories(report);
        String safeName = world.getFileName().toString().replaceAll("[^A-Za-z0-9._-]", "_");
        Path csv = report.resolve("world-" + safeName + ".csv");
        writeReport(world, result, csv);
        System.out.println("Read " + result.recordCount() + " records from " + world);
        System.out.println("Wrote " + csv.toAbsolutePath());
    }

    private record WorldResult(Map<String, Totals> byKind, long worldBytes, long anvilStorageBytes,
                               long otherWorldBytes, long emptyRegionFiles) {
        long projectedNbtZstd() { return otherWorldBytes + totals().nbtIngotBytes; }
        long projectedNbtZstd6() { return otherWorldBytes + totals().nbtZstd6IngotBytes; }
        long projectedNbtZstd9() { return otherWorldBytes + totals().nbtZstd9IngotBytes; }
        long projectedIcfZstd() { return otherWorldBytes + totals().icfIngotBytes; }
        long projectedIcfZstd6() { return otherWorldBytes + totals().icfZstd6IngotBytes; }
        long projectedIcfZstd9() { return otherWorldBytes + totals().icfZstd9IngotBytes; }
        long projectedNbtZlib() { return otherWorldBytes + totals().zlibIngotBytes; }
        long projectedIcfZlib() { return otherWorldBytes + totals().icfZlibIngotBytes; }
        long recordCount() { return totals().count; }
        Totals totals() {
            Totals total = new Totals();
            byKind.values().forEach(total::add);
            return total;
        }
    }

    private static WorldResult measureWorld(Path world) throws Exception {
        List<Path> allFiles;
        try (var paths = Files.walk(world)) {
            allFiles = paths.filter(Files::isRegularFile).sorted().toList();
        }
        long worldBytes = 0;
        long anvilStorageBytes = 0;
        Map<String, Long> anvilBytesByKind = new TreeMap<>();
        Set<Path> storageFiles = new HashSet<>();
        for (Path file : allFiles) {
            long size = Files.size(file);
            worldBytes += size;
            String extension = extension(file);
            String kind = storageKind(file);
            if (kind != null && (extension.equals(".mca") || extension.equals(".mcc"))) {
                storageFiles.add(file);
                anvilStorageBytes += size;
                anvilBytesByKind.merge(kind, size, Long::sum);
            }
        }

        Map<String, Totals> byKind = new TreeMap<>();
        anvilBytesByKind.forEach((kind, size) -> byKind.computeIfAbsent(kind, ignored -> new Totals()).anvilBytes = size);
        long emptyRegionFiles = 0;
        List<Path> regionFiles = allFiles.stream()
                .filter(path -> extension(path).equals(".mca") && REGION_FILE.matcher(path.getFileName().toString()).matches())
                .filter(path -> storageKind(path) != null).toList();
        for (Path regionFile : regionFiles) {
            String kindName = storageKind(regionFile);
            IcfPayloadKind kind = payloadKind(kindName);
            Totals totals = byKind.computeIfAbsent(kindName, ignored -> new Totals());
            byte[] fileBytes = Files.readAllBytes(regionFile);
            if (fileBytes.length == 0) { emptyRegionFiles++; continue; }
            if (fileBytes.length < ANVIL_HEADER_BYTES) throw new IOException("Truncated Anvil region: " + regionFile);
            RegionName region = parseRegionName(regionFile);
            long regionRecords = 0;
            for (int slot = 0; slot < 1024; slot++) {
                int location = readInt(fileBytes, slot * 4);
                if (location == 0) continue;
                SourceRecord record = readRecord(regionFile, fileBytes, region, slot, location);
                CompoundTag tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(record.raw())),
                        NbtAccounter.create(MAX_RAW_BYTES));
                byte[] encodedIcf = IcfCodec.encode(tag, kind, MAX_RAW_BYTES);
                CompoundTag decoded = IcfCodec.decode(encodedIcf, kind,
                        pl.fuzjajadrowa.ingot.serialization.NbtBinaryCodec.Limits.forRawBytes(MAX_RAW_BYTES));
                if (!tag.equals(decoded)) throw new IOException("ICF round-trip mismatch in " + regionFile + " slot " + slot);

                byte[] compressedZlib = zlib(record.raw());
                byte[] compressedIcfZlib = zlib(encodedIcf);
                byte[] compressedNbtZstd = ZSTD.compress(record.raw());
                byte[] compressedNbtZstd6 = ZSTD_6.compress(record.raw());
                byte[] compressedNbtZstd9 = ZSTD_9.compress(record.raw());
                byte[] compressedIcfZstd = ZSTD.compress(encodedIcf);
                byte[] compressedIcfZstd6 = ZSTD_6.compress(encodedIcf);
                byte[] compressedIcfZstd9 = ZSTD_9.compress(encodedIcf);
                totals.addRecord(record.raw(), encodedIcf, compressedZlib.length,
                        compressedNbtZstd.length, compressedNbtZstd6.length, compressedNbtZstd9.length,
                        compressedIcfZlib.length, compressedIcfZstd.length, compressedIcfZstd6.length,
                        compressedIcfZstd9.length);
                regionRecords++;
            }
            if (regionRecords > 0) {
                totals.nbtIngotBytes += IngotRegionFile.HEADER_SIZE;
                totals.icfIngotBytes += IngotRegionFile.HEADER_SIZE;
                totals.zlibIngotBytes += IngotRegionFile.HEADER_SIZE;
            }
        }
        long otherWorldBytes = worldBytes - anvilStorageBytes;
        return new WorldResult(byKind, worldBytes, anvilStorageBytes, otherWorldBytes, emptyRegionFiles);
    }

    private static SourceRecord readRecord(Path regionFile, byte[] fileBytes, RegionName region,
                                           int slot, int location) throws IOException {
        int sectorOffset = location >>> 8;
        int sectors = location & 0xff;
        long startLong = (long) sectorOffset * SECTOR_BYTES;
        if (sectorOffset < 2 || sectors == 0 || startLong + 5 > fileBytes.length) {
            throw new IOException("Invalid location entry in " + regionFile + " slot " + slot);
        }
        int start = (int) startLong;
        int length = readInt(fileBytes, start);
        if (length < 1 || (long) length + 4 > (long) sectors * SECTOR_BYTES
                || (long) start + 4 + length > fileBytes.length) {
            throw new IOException("Invalid record length in " + regionFile + " slot " + slot);
        }
        int compression = Byte.toUnsignedInt(fileBytes[start + 4]);
        byte[] payload;
        if ((compression & 0x80) != 0) {
            int chunkX = region.x() * 32 + slot % 32;
            int chunkZ = region.z() * 32 + slot / 32;
            Path external = regionFile.getParent().resolve("c." + chunkX + "." + chunkZ + ".mcc");
            if (!Files.isRegularFile(external)) throw new IOException("Missing external chunk " + external);
            payload = Files.readAllBytes(external);
        } else {
            payload = java.util.Arrays.copyOfRange(fileBytes, start + 5, start + 4 + length);
        }
        int compressionId = compression & 0x7f;
        byte[] raw = switch (compressionId) {
            case 1 -> readBounded(new GZIPInputStream(new ByteArrayInputStream(payload)));
            case 2 -> readBounded(new InflaterInputStream(new ByteArrayInputStream(payload)));
            case 3 -> payload;
            default -> throw new IOException("Unsupported Anvil compression ID " + compressionId + " in " + regionFile);
        };
        if (raw.length == 0 || raw.length > MAX_RAW_BYTES) throw new IOException("Invalid raw payload size in " + regionFile);
        return new SourceRecord(raw);
    }

    private static byte[] readBounded(java.io.InputStream input) throws IOException {
        try (input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                if (read > MAX_RAW_BYTES - output.size()) throw new IOException("Anvil chunk exceeds raw size limit");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static void writeReport(Path world, WorldResult result, Path csv) throws IOException {
        StringBuilder csvRows = new StringBuilder("world,storage_type,records,raw_nbt_bytes,raw_icf_bytes,actual_anvil_mca_mcc_bytes,nbt_zlib_payload_bytes,nbt_zstd3_payload_bytes,nbt_zstd6_payload_bytes,nbt_zstd9_payload_bytes,icf_zlib_payload_bytes,icf_zstd3_payload_bytes,icf_zstd6_payload_bytes,icf_zstd9_payload_bytes,compacted_ingot_nbt_zlib_bytes,compacted_ingot_nbt_zstd3_bytes,compacted_ingot_nbt_zstd6_bytes,compacted_ingot_nbt_zstd9_bytes,compacted_ingot_icf_zlib_bytes,compacted_ingot_icf_zstd3_bytes,compacted_ingot_icf_zstd6_bytes,compacted_ingot_icf_zstd9_bytes\n");
        result.byKind().forEach((kind, totals) -> appendRow(csvRows, world.getFileName().toString(), kind, totals));
        appendRow(csvRows, world.getFileName().toString(), "ALL", result.totals());
        Files.writeString(csv, csvRows);

        Totals totals = result.totals();
        Path metadata = csv.resolveSibling(csv.getFileName().toString().replace(".csv", "-metadata.txt"));
        String text = "world=" + world + "\n"
                + "records=" + totals.count + "\n"
                + "world_bytes_before=" + result.worldBytes() + "\n"
                + "anvil_region_storage_bytes_before=" + result.anvilStorageBytes() + "\n"
                + "empty_zero_byte_region_files_skipped=" + result.emptyRegionFiles() + "\n"
                + "other_world_bytes_unchanged=" + result.otherWorldBytes() + "\n"
                + "projected_world_bytes_nbt_zlib=" + result.projectedNbtZlib() + "\n"
                + "projected_world_bytes_nbt_zstd3=" + result.projectedNbtZstd() + "\n"
                + "projected_world_bytes_nbt_zstd6=" + result.projectedNbtZstd6() + "\n"
                + "projected_world_bytes_nbt_zstd9=" + result.projectedNbtZstd9() + "\n"
                + "projected_world_bytes_icf_zstd3=" + result.projectedIcfZstd() + "\n"
                + "projected_world_bytes_icf_zstd6=" + result.projectedIcfZstd6() + "\n"
                + "projected_world_bytes_icf_zstd9=" + result.projectedIcfZstd9() + "\n"
                + "projected_world_bytes_icf_zlib=" + result.projectedIcfZlib() + "\n"
                + "projected_world_reduction_icf_zstd3_percent=" + percent(result.worldBytes(), result.projectedIcfZstd()) + "\n"
                + "projected_world_reduction_icf_zstd6_percent=" + percent(result.worldBytes(), result.projectedIcfZstd6()) + "\n"
                + "projected_world_reduction_icf_zstd9_percent=" + percent(result.worldBytes(), result.projectedIcfZstd9()) + "\n"
                + "projected_world_reduction_icf_zlib_percent=" + percent(result.worldBytes(), result.projectedIcfZlib()) + "\n"
                + "java=" + System.getProperty("java.version") + "\n"
                + "notes=Anvil source file bytes are measured directly. Ingot sizes are estimates for compacted .ir files: 64-byte region header plus 48-byte record header and 24-byte commit block per live record. Unchanged world files are included in projected totals. Manifest, filesystem allocation rounding, and conversion-time temporary files are excluded.\n";
        Files.writeString(metadata, text);
    }

    private static void appendRow(StringBuilder rows, String world, String kind, Totals totals) {
        rows.append(String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                quote(world), quote(kind), totals.count, totals.rawNbt, totals.rawIcf, totals.anvilBytes,
                totals.nbtZlib, totals.nbtZstd, totals.nbtZstd6, totals.nbtZstd9,
                totals.icfZlib, totals.icfZstd, totals.icfZstd6, totals.icfZstd9,
                totals.zlibIngotBytes, totals.nbtIngotBytes, totals.nbtZstd6IngotBytes,
                totals.nbtZstd9IngotBytes, totals.icfZlibIngotBytes, totals.icfIngotBytes,
                totals.icfZstd6IngotBytes, totals.icfZstd9IngotBytes));
    }

    private static String percent(long before, long after) {
        return String.format(Locale.ROOT, "%.3f", (1d - (double) after / Math.max(1, before)) * 100d);
    }

    private static String quote(String text) { return "\"" + text.replace("\"", "\"\"") + "\""; }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String storageKind(Path file) {
        String parent = file.getParent().getFileName().toString();
        return switch (parent) { case "region" -> "chunk"; case "entities" -> "entities"; case "poi" -> "poi"; default -> null; };
    }

    private static IcfPayloadKind payloadKind(String kind) {
        return switch (kind) { case "entities" -> IcfPayloadKind.ENTITIES; case "poi" -> IcfPayloadKind.POI; default -> IcfPayloadKind.CHUNK; };
    }

    private static RegionName parseRegionName(Path file) {
        Matcher matcher = REGION_FILE.matcher(file.getFileName().toString());
        if (!matcher.matches()) throw new IllegalArgumentException("Invalid region filename " + file);
        return new RegionName(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
    }

    private static int readInt(byte[] bytes, int offset) throws IOException {
        if (offset < 0 || offset + 4 > bytes.length) throw new IOException("Truncated Anvil integer");
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    private static byte[] zlib(byte[] input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, false);
        try (DeflaterOutputStream output = new DeflaterOutputStream(bytes, deflater)) { output.write(input); }
        finally { deflater.end(); }
        return bytes.toByteArray();
    }
}
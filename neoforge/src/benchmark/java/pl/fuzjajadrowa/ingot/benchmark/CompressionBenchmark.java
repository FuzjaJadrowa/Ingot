package pl.fuzjajadrowa.ingot.benchmark;

import pl.fuzjajadrowa.ingot.compression.CompressionCodec;
import pl.fuzjajadrowa.ingot.compression.CompressionRegistry;
import pl.fuzjajadrowa.ingot.compression.ZstdCodec;
import pl.fuzjajadrowa.ingot.storage.IngotRegionFile;
import pl.fuzjajadrowa.ingot.storage.StorageOptions;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public final class CompressionBenchmark {
    private static final String[] DATASETS = {"vanilla", "block_entities", "low_compressibility"};
    private static final int WARMUP = 3;
    private static final int MEASURE = 10;

    private record Sample(String dataset, String file, byte[] raw) { }
    private record CodecCase(String name, CompressionCodec codec, boolean zlib, int regionLevel) { }

    private CompressionBenchmark() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: CompressionBenchmark <dataset-root> <report-directory>");
        Path root = Path.of(args[0]);
        Path report = Path.of(args[1]);
        List<Sample> samples = loadSamples(root);
        Files.createDirectories(report);
        Path csv = report.resolve("payload-results.csv");
        StringBuilder rows = new StringBuilder("dataset,file,codec,raw_bytes,compressed_bytes,payload_ratio,record_overhead_bytes,region_header_bytes,compress_mib_s,decompress_mib_s,write_us,read_us\n");
        for (Sample sample : samples) {
            List<CodecCase> cases = new ArrayList<>();
            cases.add(new CodecCase("zlib-deflate-default", null, true, 3));
            for (int level : new int[] {1, 3, 6, 9}) cases.add(new CodecCase("zstd-" + level, new ZstdCodec(level), false, level));
            for (CodecCase codecCase : cases) {
                Result result = measure(sample, codecCase);
                rows.append(String.format(Locale.ROOT, "%s,%s,%s,%d,%d,%.6f,%d,%d,%.3f,%.3f,%.3f,%.3f%n",
                        sample.dataset(), quote(sample.file()), codecCase.name(), sample.raw().length,
                        result.compressedBytes(), (double) result.compressedBytes() / Math.max(1, sample.raw().length),
                        codecCase.zlib() ? 0 : IngotRegionFile.RECORD_HEADER_SIZE + IngotRegionFile.COMMIT_SIZE,
                        codecCase.zlib() ? 0 : IngotRegionFile.HEADER_SIZE,
                        result.compressMiBPerSecond(), result.decompressMiBPerSecond(),
                        result.writeMicros(), result.readMicros()));
            }
        }
        Files.writeString(csv, rows, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        String metadata = "commit=" + System.getenv().getOrDefault("GITHUB_SHA", "unknown") + "\n"
                + "ingot=" + System.getProperty("ingot.version", "unknown") + "\n"
                + "minecraft=" + System.getProperty("ingot.minecraft", "unknown") + "\n"
                + "java=" + System.getProperty("java.version") + "\n"
                + "os=" + System.getProperty("os.name") + " " + System.getProperty("os.version") + "\n"
                + "datasets=" + samples.size() + " NBT files; input root=" + root.toAbsolutePath() + "\n"
                + "warmup_iterations=" + WARMUP + "\nmeasurement_iterations=" + MEASURE + "\n"
                + "notes=Payload sizes exclude region headers and record framing; this is not a whole-world size result.\n";
        Files.writeString(report.resolve("metadata.txt"), metadata, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println("Wrote " + csv + " and metadata.txt for " + samples.size() + " NBT samples.");
    }

    private record Result(int compressedBytes, double compressMiBPerSecond, double decompressMiBPerSecond,
                          double writeMicros, double readMicros) { }

    private static Result measure(Sample sample, CodecCase codecCase) throws Exception {
        byte[] compressed = codecCase.zlib() ? zlib(sample.raw()) : codecCase.codec().compress(sample.raw());
        byte[] roundTrip = codecCase.zlib() ? unzlib(compressed) : codecCase.codec().decompress(compressed, sample.raw().length);
        if (!java.util.Arrays.equals(sample.raw(), roundTrip)) throw new IOException("Codec validation failed: " + codecCase.name());
        CompressionCodec codec = codecCase.codec();
        long compressNanos = 0, decompressNanos = 0;
        for (int i = 0; i < WARMUP + MEASURE; i++) {
            long started = System.nanoTime();
            byte[] encoded = codecCase.zlib() ? zlib(sample.raw()) : codec.compress(sample.raw());
            long compressedAt = System.nanoTime();
            byte[] decoded = codecCase.zlib() ? unzlib(encoded) : codec.decompress(encoded, sample.raw().length);
            long finished = System.nanoTime();
            if (!java.util.Arrays.equals(sample.raw(), decoded)) throw new IOException("Round-trip mismatch");
            if (i >= WARMUP) { compressNanos += compressedAt - started; decompressNanos += finished - compressedAt; }
        }
        long writeNanos = 0, readNanos = 0;
        if (!codecCase.zlib()) {
            Path dir = Files.createTempDirectory("ingot-compression-bench-");
            try {
                StorageOptions options = StorageOptions.defaults(false);
                try (IngotRegionFile region = new IngotRegionFile(dir.resolve("r.0.0.ir"), 0, 0, options,
                        new CompressionRegistry(new ZstdCodec(codecCase.regionLevel())))) {
                    for (int i = 0; i < WARMUP + MEASURE; i++) {
                        long started = System.nanoTime();
                        region.write(0, 0, sample.raw());
                        long written = System.nanoTime();
                        byte[] decoded = region.read(0, 0);
                        long finished = System.nanoTime();
                        if (!java.util.Arrays.equals(sample.raw(), decoded)) throw new IOException("Region round-trip mismatch");
                        if (i >= WARMUP) { writeNanos += written - started; readNanos += finished - written; }
                    }
                }
            } finally {
                try (var paths = Files.walk(dir)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
                }
            }
        }
        double mib = sample.raw().length * (double) MEASURE / (1024 * 1024);
        return new Result(compressed.length, mib / (compressNanos / 1_000_000_000d),
                mib / (decompressNanos / 1_000_000_000d), codecCase.zlib() ? Double.NaN : writeNanos / (double) MEASURE / 1000d,
                codecCase.zlib() ? Double.NaN : readNanos / (double) MEASURE / 1000d);
    }

    private static List<Sample> loadSamples(Path root) throws IOException {
        List<Sample> samples = new ArrayList<>();
        for (String dataset : DATASETS) {
            Path directory = root.resolve(dataset);
            if (!Files.isDirectory(directory)) throw new IOException("Missing dataset directory: " + directory);
            int before = samples.size();
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".nbt")).sorted().toList()) {
                    byte[] raw = Files.readAllBytes(path);
                    if (raw.length > 64 * 1024 * 1024) throw new IOException("Sample exceeds Ingot's default raw limit: " + path);
                    samples.add(new Sample(dataset, directory.relativize(path).toString().replace('\\', '/'), raw));
                }
            }
            if (samples.size() == before) throw new IOException("Dataset directory contains no .nbt samples: " + directory);
        }
        if (samples.isEmpty()) throw new IOException("No .nbt samples found under the three required dataset directories");
        return samples;
    }

    private static byte[] zlib(byte[] input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, false);
        try (DeflaterOutputStream stream = new DeflaterOutputStream(out, deflater)) { stream.write(input); }
        finally { deflater.end(); }
        return out.toByteArray();
    }
    private static byte[] unzlib(byte[] input) throws IOException {
        try (InflaterInputStream stream = new InflaterInputStream(new ByteArrayInputStream(input));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            stream.transferTo(out);
            return out.toByteArray();
        }
    }
    private static String quote(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
}
package pl.fuzjajadrowa.ingot.benchmark;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import pl.fuzjajadrowa.ingot.compression.ZstdCodec;
import pl.fuzjajadrowa.ingot.serialization.IcfCodec;
import pl.fuzjajadrowa.ingot.serialization.IcfPayloadKind;
import pl.fuzjajadrowa.ingot.serialization.NbtBinaryCodec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public final class SerializationBenchmark {
    private static final String[] DATASETS = {"vanilla", "block_entities", "low_compressibility"};
    private static final int WARMUP = 2;
    private static final int MEASURE = 5;
    private static final int MAX_RAW_BYTES = 64 * 1024 * 1024;

    private record Sample(String dataset, String file, CompoundTag tag, byte[] nbt) { }
    private record Measurements(byte[] payload, byte[] zlib, byte[] zstd, double nbtSerializeMicros,
                                double icfSerializeMicros, double nbtDeserializeMicros,
                                double icfDeserializeMicros) { }

    private SerializationBenchmark() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException(
                "Usage: SerializationBenchmark <dataset-root> <report-directory>");
        Path root = Path.of(args[0]);
        Path report = Path.of(args[1]);
        List<Sample> samples = loadSamples(root);
        Files.createDirectories(report);
        StringBuilder rows = new StringBuilder("dataset,file,nbt_bytes,icf_bytes,nbt_zlib_bytes,nbt_zstd3_bytes,icf_zstd3_bytes,nbt_serialize_us,icf_serialize_us,nbt_deserialize_us,icf_deserialize_us\n");
        for (Sample sample : samples) {
            Measurements measured = measure(sample);
            rows.append(String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%d,%.3f,%.3f,%.3f,%.3f%n",
                    sample.dataset(), quote(sample.file()), sample.nbt().length, measured.payload().length,
                    measured.zlib().length, new ZstdCodec(3).compress(sample.nbt()).length,
                    measured.zstd().length, measured.nbtSerializeMicros(), measured.icfSerializeMicros(),
                    measured.nbtDeserializeMicros(), measured.icfDeserializeMicros()));
        }
        Files.writeString(report.resolve("serialization-results.csv"), rows);
        Files.writeString(report.resolve("serialization-metadata.txt"),
                "minecraft=" + System.getProperty("ingot.minecraft", "unknown") + "\n"
                + "java=" + System.getProperty("java.version") + "\n"
                + "os=" + System.getProperty("os.name") + " " + System.getProperty("os.version") + "\n"
                + "samples=" + samples.size() + "\nwarmup_iterations=" + WARMUP
                + "\nmeasurement_iterations=" + MEASURE);
        System.out.println("Wrote serialization-results.csv for " + samples.size() + " real chunk samples.");
    }

    private static Measurements measure(Sample sample) throws Exception {
        byte[] icf = IcfCodec.encode(sample.tag(), IcfPayloadKind.CHUNK, MAX_RAW_BYTES);
        byte[] nbt = encodeNbt(sample.tag());
        byte[] zlib = zlib(sample.nbt());
        byte[] zstd = new ZstdCodec(3).compress(icf);
        if (!sample.tag().equals(NbtIo.read(new DataInputStream(new ByteArrayInputStream(nbt)),
                NbtAccounter.create(MAX_RAW_BYTES)))) throw new IOException("NBT round-trip mismatch: " + sample.file());
        if (!sample.tag().equals(IcfCodec.decode(icf, IcfPayloadKind.CHUNK,
                NbtBinaryCodec.Limits.forRawBytes(MAX_RAW_BYTES)))) throw new IOException("ICF round-trip mismatch: " + sample.file());
        return new Measurements(icf, zlib, zstd, measureSerialization(sample, false),
                measureSerialization(sample, true), measureDeserialization(sample, false),
                measureDeserialization(sample, true));
    }

    private static double measureSerialization(Sample sample, boolean icf) throws Exception {
        long elapsed = 0;
        for (int iteration = 0; iteration < WARMUP + MEASURE; iteration++) {
            long start = System.nanoTime();
            byte[] encoded = icf ? IcfCodec.encode(sample.tag(), IcfPayloadKind.CHUNK, MAX_RAW_BYTES)
                    : encodeNbt(sample.tag());
            long finish = System.nanoTime();
            if (iteration >= WARMUP) elapsed += finish - start;
            if (encoded.length == 0) throw new IOException("Unexpected empty serialization");
        }
        return elapsed / (double) MEASURE / 1000d;
    }

    private static double measureDeserialization(Sample sample, boolean icf) throws Exception {
        byte[] encoded = icf ? IcfCodec.encode(sample.tag(), IcfPayloadKind.CHUNK, MAX_RAW_BYTES) : sample.nbt();
        long elapsed = 0;
        for (int iteration = 0; iteration < WARMUP + MEASURE; iteration++) {
            long start = System.nanoTime();
            CompoundTag decoded = icf
                    ? IcfCodec.decode(encoded, IcfPayloadKind.CHUNK, NbtBinaryCodec.Limits.forRawBytes(MAX_RAW_BYTES))
                    : NbtIo.read(new DataInputStream(new ByteArrayInputStream(encoded)), NbtAccounter.create(MAX_RAW_BYTES));
            long finish = System.nanoTime();
            if (!sample.tag().equals(decoded)) throw new IOException("Deserialization mismatch: " + sample.file());
            if (iteration >= WARMUP) elapsed += finish - start;
        }
        return elapsed / (double) MEASURE / 1000d;
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
                    if (raw.length > MAX_RAW_BYTES) throw new IOException("Sample exceeds raw byte limit: " + path);
                    CompoundTag tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(raw)),
                            NbtAccounter.create(MAX_RAW_BYTES));
                    samples.add(new Sample(dataset, directory.relativize(path).toString().replace('\\', '/'), tag, raw));
                }
            }
            if (samples.size() == before) throw new IOException("Dataset directory contains no .nbt samples: " + directory);
        }
        return samples;
    }

    private static byte[] encodeNbt(CompoundTag tag) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) { NbtIo.write(tag, output); }
        return bytes.toByteArray();
    }

    private static byte[] zlib(byte[] input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, false);
        try (DeflaterOutputStream output = new DeflaterOutputStream(bytes, deflater)) { output.write(input); }
        finally { deflater.end(); }
        return bytes.toByteArray();
    }

    private static String quote(String value) { return "\"" + value.replace("\"", "\"\"") + "\""; }
}
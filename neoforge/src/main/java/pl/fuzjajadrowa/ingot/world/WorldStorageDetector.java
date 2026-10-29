package pl.fuzjajadrowa.ingot.world;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class WorldStorageDetector {
    private static final Pattern ANVIL_REGION = Pattern.compile("r\\.-?\\d+\\.-?\\d+\\.mca");
    private static final Pattern ANVIL_EXTERNAL_CHUNK = Pattern.compile("c\\.-?\\d+\\.-?\\d+\\.mcc");
    private static final Pattern INGOT_REGION = Pattern.compile(
            "r\\.-?\\d+\\.-?\\d+\\.ir(?:\\.(?:uncommitted|compacting)-[^/]+)?(?:\\.lock)?");
    private static final List<String> STORAGE_NAMES = List.of("region", "entities", "poi");

    record Detection(boolean hasAnvil, boolean hasIngot, List<Path> unsafePaths) {
        boolean hasUnsafePaths() {
            return !unsafePaths.isEmpty();
        }
    }

    private static final class Scan {
        private boolean anvil;
        private boolean ingot;
        private final List<Path> unsafe = new ArrayList<>();

        private Detection result() {
            return new Detection(anvil, ingot, List.copyOf(unsafe));
        }
    }

    private WorldStorageDetector() { }

    static Detection inspect(Path worldRoot) throws IOException {
        Scan scan = new Scan();
        inspectVanillaDimension(worldRoot, scan);
        inspectLegacyDimension(worldRoot.resolve("DIM-1"), scan);
        inspectLegacyDimension(worldRoot.resolve("DIM1"), scan);
        inspectModdedDimensions(worldRoot.resolve("dimensions"), scan);
        return scan.result();
    }

    private static void inspectVanillaDimension(Path root, Scan scan) throws IOException {
        inspectDimensionDirectories(root, scan);
    }

    private static void inspectLegacyDimension(Path dimensionRoot, Scan scan) throws IOException {
        if (Files.isSymbolicLink(dimensionRoot)) {
            scan.unsafe.add(dimensionRoot);
            return;
        }
        if (Files.isDirectory(dimensionRoot, LinkOption.NOFOLLOW_LINKS)) {
            inspectDimensionDirectories(dimensionRoot, scan);
        } else if (Files.exists(dimensionRoot, LinkOption.NOFOLLOW_LINKS)) {
            scan.unsafe.add(dimensionRoot);
        }
    }

    private static void inspectDimensionDirectories(Path dimensionRoot, Scan scan) throws IOException {
        for (String name : STORAGE_NAMES) {
            Path storage = dimensionRoot.resolve(name);
            if (Files.isDirectory(storage, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(storage)) {
                inspectStorageDirectory(storage, scan);
            } else if (Files.exists(storage, LinkOption.NOFOLLOW_LINKS)) {
                scan.unsafe.add(storage);
            }
        }
    }

    private static void inspectModdedDimensions(Path dimensionsRoot, Scan scan) throws IOException {
        if (Files.isSymbolicLink(dimensionsRoot)) {
            scan.unsafe.add(dimensionsRoot);
            return;
        }
        if (!Files.isDirectory(dimensionsRoot, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(dimensionsRoot, LinkOption.NOFOLLOW_LINKS)) scan.unsafe.add(dimensionsRoot);
            return;
        }

        Files.walkFileTree(dimensionsRoot, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                if (attrs.isSymbolicLink()) {
                    scan.unsafe.add(dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Path relative = dimensionsRoot.relativize(dir);
                if (!dir.equals(dimensionsRoot) && relative.getNameCount() >= 3
                        && isStorageName(dir.getFileName().toString())) {
                    inspectStorageDirectory(dir, scan);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isSymbolicLink()) {
                    scan.unsafe.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void inspectStorageDirectory(Path storage, Scan scan) throws IOException {
        if (Files.isSymbolicLink(storage)) {
            scan.unsafe.add(storage);
            return;
        }
        if (!Files.isDirectory(storage, LinkOption.NOFOLLOW_LINKS)) return;

        try (Stream<Path> children = Files.list(storage)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (name.equals("ingot")) {
                    inspectIngotDirectory(child, scan);
                    continue;
                }

                boolean anvilName = isAnvilFileName(name);
                if (!anvilName) {
                    if (looksLikeMalformedAnvilArtifact(name)) scan.unsafe.add(child);
                    continue;
                }
                if (Files.isSymbolicLink(child)) {
                    scan.unsafe.add(child);
                } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    scan.anvil = true;
                } else {
                    scan.unsafe.add(child);
                }
            }
        }
    }

    private static void inspectIngotDirectory(Path ingotDirectory, Scan scan) throws IOException {
        if (Files.isSymbolicLink(ingotDirectory)) {
            scan.unsafe.add(ingotDirectory);
            return;
        }
        if (!Files.isDirectory(ingotDirectory, LinkOption.NOFOLLOW_LINKS)) {
            scan.unsafe.add(ingotDirectory);
            return;
        }

        try (Stream<Path> children = Files.list(ingotDirectory)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (!INGOT_REGION.matcher(name).matches()) {
                    if (looksLikeIngotArtifact(name)) scan.unsafe.add(child);
                    continue;
                }
                if (Files.isSymbolicLink(child)) {
                    scan.unsafe.add(child);
                } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                    scan.ingot = true;
                } else {
                    scan.unsafe.add(child);
                }
            }
        }
    }

    private static boolean isAnvilFileName(String name) {
        return ANVIL_REGION.matcher(name).matches() || ANVIL_EXTERNAL_CHUNK.matcher(name).matches();
    }

    private static boolean looksLikeMalformedAnvilArtifact(String name) {
        return (name.startsWith("r.") && name.endsWith(".mca"))
                || (name.startsWith("c.") && name.endsWith(".mcc"));
    }

    private static boolean looksLikeIngotArtifact(String name) {
        return name.endsWith(".ir") || name.contains(".ir.");
    }

    private static boolean isStorageName(String name) {
        return STORAGE_NAMES.contains(name);
    }
}
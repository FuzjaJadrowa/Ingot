package pl.fuzjajadrowa.ingot.world;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class IngotWorldManager {
    private static final Map<Path, Session> WORLDS = new LinkedHashMap<>();
    private IngotWorldManager() {}

    public static final class Session {
        private final Path root;
        private final Path selectedRoot;
        private final AtomicReference<IngotStorageException> failure = new AtomicReference<>();
        private final Set<AutoCloseable> resources = new HashSet<>();
        private Session(Path root, Path selectedRoot) { this.root = root; this.selectedRoot = selectedRoot; }
        public Path root() { return root; }
        public synchronized void register(AutoCloseable resource) { resources.add(resource); }
        public synchronized void unregister(AutoCloseable resource) { resources.remove(resource); }
        private void closeResources() {
            List<AutoCloseable> pending;
            synchronized (this) { pending = List.copyOf(resources); resources.clear(); }
            Exception problem = null;
            for (AutoCloseable resource : pending) {
                try { resource.close(); }
                catch (Exception e) {
                    if (problem == null) problem = e;
                    else if (problem != e) problem.addSuppressed(e);
                }
            }
            if (problem != null) throw fail(problem);
        }
        public IngotStorageException fail(Throwable cause) {
            IngotStorageException problem = new IngotStorageException("Ingot storage failed for " + root
                    + "; further world I/O is blocked. Inspect the error and restore/repair data before reopening.", cause);
            failure.compareAndSet(null, problem);
            return failure.get();
        }
        public void checkHealthy() {
            IngotStorageException problem = failure.get();
            if (problem != null) throw problem;
        }
    }

    public static synchronized Session openWorld(Path directory) throws IOException {
        Path root = directory.toRealPath();
        if (WORLDS.containsKey(root)) throw new IOException("World already registered: " + root);
        for (Path other : WORLDS.keySet()) {
            if (root.startsWith(other) || other.startsWith(root)) throw new IOException("Overlapping world roots");
        }
        Path manifest = root.resolve(IngotWorldManifest.FILE_NAME);
        boolean marked = !Files.notExists(manifest);
        if (!Files.notExists(root.resolve(IngotWorldManifest.FILE_NAME + ".pending"))) {
            throw new IOException("Interrupted Ingot activation; inspect ingot-manifest.pending in " + root);
        }
        if (marked) IngotWorldManifest.read(root);
        boolean[] data = {false, false, false}; // active Anvil, active Ingot, unsafe active path
        if (Files.isSymbolicLink(root.resolve("dimensions"))) data[2] = true;
        try (var children = Files.list(root)) {
            if (children.anyMatch(p -> isLegacyDimensionName(p.getFileName().toString()) && Files.isSymbolicLink(p))) data[2] = true;
        }
        for (Path storageDirectory : storageDirectories(root, data)) {
            Path realStorage = storageDirectory.toRealPath();
            if (!realStorage.startsWith(root)) { data[2] = true; continue; }
            Files.walkFileTree(storageDirectory, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (!dir.equals(storageDirectory) && attrs.isSymbolicLink()) { data[2] = true; return FileVisitResult.SKIP_SUBTREE; }
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isSymbolicLink()) { data[2] = true; return FileVisitResult.CONTINUE; }
                    String name = file.getFileName().toString();
                    if (name.endsWith(".mca") || name.endsWith(".mcc")) data[0] = true;
                    if (name.endsWith(".ir") || name.contains(".ir.uncommitted-")) data[1] = true;
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        if (data[1] && !marked) throw new IOException("Ingot data exists without its manifest; refusing Anvil fallback: " + root);
        if (data[2]) throw new IOException("Linked or aliased active storage path found; resolve it before activation: " + root);
        if (data[0]) {
            if (marked) throw new IOException("Anvil data found; Ingot/Anvil mixed backends refused: " + root);
            return null;
        }
        if (!marked) new IngotWorldManifest(UUID.randomUUID()).write(root);
        Session session = new Session(root, directory.toAbsolutePath().normalize());
        WORLDS.put(root, session);
        return session;
    }

    private static List<Path> storageDirectories(Path root, boolean[] unsafePath) throws IOException {
        List<Path> result = new java.util.ArrayList<>();
        addStorageDirectories(root, result);
        Path dimensions = root.resolve("dimensions");
        if (Files.isDirectory(dimensions, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(dimensions)) {
            Files.walkFileTree(dimensions, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (attrs.isSymbolicLink()) {
                        if (dir.getNameCount() >= dimensions.getNameCount() + 2) unsafePath[2] = true;
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (dir.getFileName() != null && isStorageName(dir.getFileName().toString())) {
                        result.add(dir);
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        try (var children = Files.list(root)) {
            for (Path child : children.filter(p -> isLegacyDimensionName(p.getFileName().toString())).toList()) {
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(child)) addStorageDirectories(child, result);
            }
        }
        return result;
    }

    private static void addStorageDirectories(Path dimensionRoot, List<Path> result) throws IOException {
        for (String name : List.of("region", "entities", "poi")) {
            Path candidate = dimensionRoot.resolve(name);
            if (Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(candidate)) result.add(candidate);
        }
    }

    private static boolean isStorageName(String name) {
        return name.equals("region") || name.equals("entities") || name.equals("poi");
    }

    private static boolean isLegacyDimensionName(String name) {
        return name.equals("DIM-1") || name.equals("DIM1");
    }

    public static synchronized Session forFolder(Path folder) throws IOException {
        Path absolute = folder.toAbsolutePath().normalize();
        for (Session session : WORLDS.values()) {
            Path boundary = absolute.startsWith(session.selectedRoot) ? session.selectedRoot : session.root;
            if (absolute.startsWith(boundary)) {
                Path current = absolute;
                while (current != null && current.startsWith(boundary) && !current.equals(boundary)) {
                    if (Files.isSymbolicLink(current)) throw new IOException("Linked Ingot storage path: " + current);
                    if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)
                            && !current.toRealPath().equals(session.root.resolve(boundary.relativize(current)))) {
                        throw new IOException("Aliased Ingot storage path: " + current);
                    }
                    current = current.getParent();
                }
                return session;
            }
        }
        return null;
    }

    public static void closeWorld(Session session) {
        if (session == null) return;
        try { session.closeResources(); }
        finally { synchronized (IngotWorldManager.class) { WORLDS.remove(session.root, session); } }
    }
}
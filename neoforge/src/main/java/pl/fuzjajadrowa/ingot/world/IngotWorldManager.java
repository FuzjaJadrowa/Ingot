package pl.fuzjajadrowa.ingot.world;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

public final class IngotWorldManager {
    private static final Map<Path, Session> WORLDS = new LinkedHashMap<>();

    private IngotWorldManager() { }

    public static final class Session {
        private final Path root;
        private final Path selectedRoot;
        private final AtomicReference<IngotStorageException> failure = new AtomicReference<>();
        private final Set<AutoCloseable> resources = new HashSet<>();

        private Session(Path root, Path selectedRoot) {
            this.root = root;
            this.selectedRoot = selectedRoot;
        }

        public Path root() { return root; }

        public synchronized void register(AutoCloseable resource) { resources.add(resource); }

        public synchronized void unregister(AutoCloseable resource) { resources.remove(resource); }

        private void closeResources() {
            List<AutoCloseable> pending;
            synchronized (this) {
                pending = List.copyOf(resources);
                resources.clear();
            }
            Exception problem = null;
            for (AutoCloseable resource : pending) {
                try {
                    resource.close();
                } catch (Exception e) {
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
        Path selectedRoot = directory.toAbsolutePath().normalize();
        Path root = directory.toRealPath();
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("World path is not a directory: " + root);
        }
        if (WORLDS.containsKey(root)) throw new IOException("World already registered: " + root);
        for (Path other : WORLDS.keySet()) {
            if (root.startsWith(other) || other.startsWith(root)) throw new IOException("Overlapping world roots: " + root + " and " + other);
        }

        Path manifest = root.resolve(IngotWorldManifest.FILE_NAME);
        Path pending = root.resolve(IngotWorldManifest.FILE_NAME + ".pending");
        boolean marked = checkedExists(manifest, "manifest");
        if (checkedExists(pending, "pending manifest")) {
            throw new IOException("Interrupted Ingot activation; inspect " + pending
                    + " and the world data before reopening. The file was left untouched.");
        }
        if (marked) IngotWorldManifest.read(root);

        WorldStorageDetector.Detection data = WorldStorageDetector.inspect(root);
        if (data.hasUnsafePaths()) {
            throw new IOException("A symbolic link or ambiguous path was found in active world storage; refusing to choose a backend: "
                    + data.unsafePaths());
        }
        if (data.hasAnvil() && data.hasIngot()) {
            throw new IOException("Active Anvil and Ingot data coexist in this world; mixed backends are not supported: " + root);
        }
        if (data.hasIngot() && !marked) {
            throw new IOException("Ingot region data or recovery candidates exist without an Ingot manifest; refusing Anvil fallback: " + root);
        }
        if (data.hasAnvil()) {
            if (marked) {
                throw new IOException("Anvil region data exists in a world marked for Ingot; mixed backends are not supported: " + root);
            }
            return null;
        }

        // A manifest is created only after inspection has completed without ambiguity.
        if (!marked) new IngotWorldManifest(UUID.randomUUID()).write(root);
        Session session = new Session(root, selectedRoot);
        WORLDS.put(root, session);
        return session;
    }

    private static boolean checkedExists(Path path, String description) throws IOException {
        boolean exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
        if (exists) return true;
        if (Files.notExists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        throw new IOException("Cannot determine whether the world " + description + " exists: " + path);
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
        try {
            session.closeResources();
        } finally {
            synchronized (IngotWorldManager.class) {
                WORLDS.remove(session.root, session);
            }
        }
    }
}

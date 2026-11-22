package sh.aki.tickcapacity.bench;

import org.bukkit.Difficulty;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import sh.aki.tickcapacity.compat.ServerCompat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.stream.Stream;

/**
 * The dedicated benchmark world. A marker file identifies worlds created by this plugin; any other world
 * with the configured name is left alone.
 */
final class BenchmarkWorld {

    private static final String MARKER = "tickcapacity-benchmark.marker";
    /** Upper bound for waiting on chunk writes before a world that is to be deleted gets unloaded. */
    private static final int RELEASE_MAX_SECONDS = 120;
    private static final int RELEASE_CHUNK_THRESHOLD = 64;

    private final Plugin plugin;
    private final Server server;
    private final Executor io;
    private World releasing;

    BenchmarkWorld(Plugin plugin, Executor io) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.io = io;
    }

    /** True if a folder with this name exists but was not created by the plugin. */
    boolean conflicts(String name) {
        Path folder = server.getWorldContainer().toPath().resolve(name);
        return Files.isDirectory(folder) && !Files.exists(folder.resolve(MARKER));
    }

    boolean isReleasing() {
        return releasing != null && server.getWorld(releasing.getUID()) != null;
    }

    /**
     * Loads or creates the world and prepares it for measuring.
     *
     * @return null if a world with that name exists and is not ours
     */
    World open(String name, long seed) {
        World existing = server.getWorld(name);
        long startedAt = System.currentTimeMillis();
        World world = existing != null ? existing : server.createWorld(new WorldCreator(name)
                .seed(seed)
                .environment(World.Environment.NORMAL)
                .type(WorldType.NORMAL)
                .generateStructures(false)); // villages would add villagers we do not control
        if (world == null) {
            return null;
        }
        Path folder = ServerCompat.worldFolder(world);
        if (!Files.exists(folder.resolve(MARKER))) {
            if (!createdJustNow(folder, startedAt)) {
                if (existing == null) {
                    server.unloadWorld(world, false);
                }
                return null;
            }
            try {
                Files.writeString(folder.resolve(MARKER), "Created by TickCapacity for benchmarking. Safe to delete.\n");
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Could not write benchmark marker", e);
            }
        }
        world.setDifficulty(Difficulty.NORMAL); // peaceful would remove the hostile mobs
        for (ServerCompat.Rule rule : ServerCompat.Rule.values()) {
            if (!ServerCompat.setRule(world, rule, false)) {
                plugin.getLogger().warning("Could not set game rule " + rule + " in the benchmark world");
            }
        }
        world.setTime(18000); // night, so zombies and skeletons do not burn
        world.setStorm(false);
        return world;
    }

    private static boolean createdJustNow(Path folder, long startedAt) {
        Path levelDat = folder.resolve("level.dat");
        try {
            return !Files.exists(levelDat) || Files.getLastModifiedTime(levelDat).toMillis() >= startedAt - 5_000;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Releases the world after a run.
     *
     * <p>Unloading a world blocks the main thread until every queued chunk write is done, which after a
     * benchmark can take 10-20 seconds. A kept world is therefore left loaded: with its tickets gone the
     * server unloads the chunks in the background and the next run reuses it. A world that is to be deleted
     * is unloaded only after its chunk count has been low for a while.
     */
    void release(World world, boolean delete, boolean now) {
        if (!delete) {
            return;
        }
        if (now) {
            unload(world, true);
            return;
        }
        int drainSeconds = Math.clamp(world.getChunkCount() / 1000 + 5, 5, 60);
        releasing = world;
        int[] waited = {0};
        int[] quiet = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = server.getScheduler().runTaskTimer(plugin, () -> {
            waited[0]++;
            if (server.getWorld(world.getUID()) == null) {
                releasing = null;
                task[0].cancel();
                return;
            }
            quiet[0] = world.getChunkCount() <= RELEASE_CHUNK_THRESHOLD ? quiet[0] + 1 : 0;
            if (quiet[0] >= drainSeconds || waited[0] >= RELEASE_MAX_SECONDS) {
                task[0].cancel();
                unload(world, true);
                releasing = null;
            }
        }, 20L, 20L);
    }

    /** Finishes a pending release immediately (plugin disable). */
    void releaseNow() {
        if (isReleasing()) {
            unload(releasing, true);
        }
        releasing = null;
    }

    private void unload(World world, boolean delete) {
        Path folder = ServerCompat.worldFolder(world);
        boolean unloaded = server.unloadWorld(world, !delete);
        if (unloaded && delete && Files.exists(folder.resolve(MARKER))) {
            io.execute(() -> deleteRecursively(folder));
        }
    }

    private void deleteRecursively(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Could not delete benchmark world " + root, e);
        }
    }
}

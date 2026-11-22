package sh.aki.tickcapacity.bench;

import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import sh.aki.tickcapacity.config.Settings;

/**
 * State shared by everything that creates load during one benchmark. Async callbacks capture
 * {@link #generation} and do nothing if it has changed, which is how abandoned work gets cancelled.
 */
final class LoadContext {

    final Plugin plugin;
    final World world;
    final Settings.Benchmark config;
    final int radius;

    int generation;
    int spawnRequested;
    int spawnSucceeded;
    int pendingSpawns;

    LoadContext(Plugin plugin, World world, Settings.Benchmark config, int radius) {
        this.plugin = plugin;
        this.world = world;
        this.config = config;
        this.radius = radius;
    }

    boolean isCurrent(int generation) {
        return this.generation == generation;
    }

    /** Invalidates pending chunk loads and spawns. */
    void cancelPending() {
        generation++;
        pendingSpawns = 0;
    }
}

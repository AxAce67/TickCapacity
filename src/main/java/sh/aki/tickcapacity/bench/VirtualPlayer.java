package sh.aki.tickcapacity.bench;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.event.entity.CreatureSpawnEvent;
import sh.aki.tickcapacity.compat.ServerCompat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Level;

/**
 * Stand-in for a player: a square of ticking chunks plus a set of mobs around its centre. It can walk in a
 * straight line, in which case the square follows it and new terrain gets generated.
 */
final class VirtualPlayer {

    private static final int SPAWN_RADIUS_BLOCKS = 32;
    private static final double GOLDEN_ANGLE = 2.399963229728653;

    private final LoadContext ctx;
    private final Random random;
    private final double dirX;
    private final double dirZ;
    private final List<Entity> mobs = new ArrayList<>();
    private double x;
    private double z;
    private int centerX;
    private int centerZ;
    private boolean active = true;

    VirtualPlayer(LoadContext ctx, int index, int run, int homeChunkX, int homeChunkZ) {
        this.ctx = ctx;
        this.random = new Random(ctx.config.seed() * 31 + index + 1_000_003L * run);
        double angle = index * GOLDEN_ANGLE;
        this.dirX = Math.cos(angle);
        this.dirZ = Math.sin(angle);
        this.centerX = homeChunkX;
        this.centerZ = homeChunkZ;
        this.x = homeChunkX * 16 + 8;
        this.z = homeChunkZ * 16 + 8;
    }

    int centerX() {
        return centerX;
    }

    int centerZ() {
        return centerZ;
    }

    /** Spawns the mobs. The chunks are ticketed by {@link TerrainLoader} as they finish loading. */
    void spawnMobs() {
        for (Map.Entry<EntityType, Integer> e : ctx.config.entitiesPerPlayer().entrySet()) {
            for (int i = 0; i < e.getValue(); i++) {
                spawnNear(e.getKey(), 0);
            }
        }
    }

    void move(double seconds) {
        double step = ctx.config.movementBlocksPerSecond() * seconds;
        x += dirX * step;
        z += dirZ * step;
        int ncx = Math.floorDiv((int) Math.floor(x), 16);
        int ncz = Math.floorDiv((int) Math.floor(z), 16);
        if (ncx != centerX || ncz != centerZ) {
            slideTo(ncx, ncz);
        }
        replaceStrayMobs();
    }

    private void slideTo(int ncx, int ncz) {
        int r = ctx.radius;
        int generation = ctx.generation;
        // Ticket the new square before releasing the old one so shared chunks never unload in between.
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int cx = ncx + dx;
                int cz = ncz + dz;
                if (ctx.world.isChunkLoaded(cx, cz)) {
                    ctx.world.addPluginChunkTicket(cx, cz, ctx.plugin);
                    continue;
                }
                ctx.world.getChunkAtAsync(cx, cz, true).thenAccept(chunk -> {
                    if (ctx.isCurrent(generation) && active && Math.abs(cx - centerX) <= r
                            && Math.abs(cz - centerZ) <= r) {
                        ctx.world.addPluginChunkTicket(cx, cz, ctx.plugin);
                    }
                });
            }
        }
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int cx = centerX + dx;
                int cz = centerZ + dz;
                if (Math.abs(cx - ncx) > r || Math.abs(cz - ncz) > r) {
                    ctx.world.removePluginChunkTicket(cx, cz, ctx.plugin);
                }
            }
        }
        centerX = ncx;
        centerZ = ncz;
    }

    /** Mobs that fell behind (or unloaded with their chunk) are respawned ahead of the player. */
    private void replaceStrayMobs() {
        int spread = spread();
        double maxDistance = spread + 24;
        for (int i = mobs.size() - 1; i >= 0; i--) {
            Entity mob = mobs.get(i);
            boolean lost = !mob.isValid() || mob.getWorld() != ctx.world;
            if (lost || Math.hypot(mob.getLocation().getX() - x, mob.getLocation().getZ() - z) > maxDistance) {
                mobs.remove(i);
                if (!lost) {
                    mob.remove();
                }
                spawnNear(mob.getType(), spread * 0.5);
            }
        }
    }

    private int spread() {
        return Math.min(SPAWN_RADIUS_BLOCKS, ctx.radius * 16);
    }

    private void spawnNear(EntityType type, double ahead) {
        int spread = spread();
        int sx = (int) Math.floor(x + dirX * ahead) + random.nextInt(2 * spread + 1) - spread;
        int sz = (int) Math.floor(z + dirZ * ahead) + random.nextInt(2 * spread + 1) - spread;
        int generation = ctx.generation;
        ctx.spawnRequested++;
        ctx.pendingSpawns++;
        ctx.world.getChunkAtAsync(sx >> 4, sz >> 4).whenComplete((chunk, error) -> {
            if (!ctx.isCurrent(generation)) {
                return;
            }
            ctx.pendingSpawns--;
            if (error != null) {
                ctx.plugin.getLogger().log(Level.WARNING, "Could not load chunk for a benchmark mob", error);
            } else if (active) {
                try {
                    spawnAt(type, sx, sz);
                } catch (RuntimeException | LinkageError e) {
                    ctx.plugin.getLogger().log(Level.WARNING, "Could not spawn benchmark mob " + type, e);
                }
            }
        });
    }

    private void spawnAt(EntityType type, int sx, int sz) {
        Location loc = new Location(ctx.world, sx + 0.5, ctx.world.getHighestBlockYAt(sx, sz) + 1, sz + 0.5);
        Entity entity = ctx.world.spawnEntity(loc, type, CreatureSpawnEvent.SpawnReason.CUSTOM, e -> {
            e.setPersistent(false); // never written to disk, even if the server crashes mid-run
            e.setSilent(true);
            if (e instanceof Mob mob) {
                ServerCompat.preventDespawn(mob);
            }
        });
        if (entity.isValid()) {
            mobs.add(entity);
            ctx.spawnSucceeded++;
        }
    }

    void removeMobs() {
        active = false;
        for (Entity e : mobs) {
            if (e.isValid()) {
                e.remove();
            }
        }
        mobs.clear();
    }

    /** Removes the mobs and releases the chunks, e.g. when a step is rolled back. */
    void deactivate() {
        removeMobs();
        int r = ctx.radius;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                ctx.world.removePluginChunkTicket(centerX + dx, centerZ + dz, ctx.plugin);
            }
        }
    }
}

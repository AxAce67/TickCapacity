package sh.aki.tickcapacity.bench;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Loads (and generates if needed) the chunks for new virtual players off the main thread and tickets them
 * once loaded. Ticketing an unloaded chunk makes Bukkit load it synchronously, which froze the server for
 * over ten seconds when a few hundred chunks were added at once.
 */
final class TerrainLoader {

    private static final int MAX_IN_FLIGHT = 32;

    /** {chunkX, chunkZ, ticket ? 1 : 0}. The outer ring is only generated so the ticked chunks have neighbours. */
    private final Deque<int[]> queue = new ArrayDeque<>();
    private final LoadContext ctx;
    private IntConsumer onQuarter = pct -> { };
    private int total;
    private int done;
    private int inFlight;
    private int lastReported;

    TerrainLoader(LoadContext ctx) {
        this.ctx = ctx;
    }

    void load(List<VirtualPlayer> players, IntConsumer onQuarter) {
        this.onQuarter = onQuarter;
        queue.clear();
        int r = ctx.radius;
        int outer = r + 1;
        for (VirtualPlayer vp : players) {
            for (int dx = -outer; dx <= outer; dx++) {
                for (int dz = -outer; dz <= outer; dz++) {
                    boolean ticket = Math.abs(dx) <= r && Math.abs(dz) <= r;
                    queue.add(new int[]{vp.centerX() + dx, vp.centerZ() + dz, ticket ? 1 : 0});
                }
            }
        }
        total = queue.size();
        done = 0;
        inFlight = 0;
        lastReported = 0;
        pump(ctx.generation);
    }

    private void pump(int generation) {
        while (ctx.isCurrent(generation) && inFlight < MAX_IN_FLIGHT && !queue.isEmpty()) {
            int[] c = queue.poll();
            inFlight++;
            ctx.world.getChunkAtAsync(c[0], c[1], true).whenComplete((chunk, error) -> {
                if (!ctx.isCurrent(generation)) {
                    return;
                }
                if (error == null && c[2] == 1) {
                    ctx.world.addPluginChunkTicket(c[0], c[1], ctx.plugin);
                }
                inFlight--;
                done++;
                int pct = percent();
                if (pct / 25 > lastReported / 25) {
                    lastReported = pct;
                    onQuarter.accept(pct);
                }
                pump(generation);
            });
        }
    }

    boolean isDone() {
        return done >= total;
    }

    int percent() {
        return total == 0 ? 100 : done * 100 / total;
    }

    void clear() {
        queue.clear();
    }
}

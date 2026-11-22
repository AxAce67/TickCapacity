package sh.aki.tickcapacity.monitor;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

/** Chunks generated for the first time. Callers keep their own snapshot of the total and subtract. */
public final class ChunkGenerationCounter implements Listener {

    private long total;

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (event.isNewChunk()) {
            total++;
        }
    }

    public long total() {
        return total;
    }
}

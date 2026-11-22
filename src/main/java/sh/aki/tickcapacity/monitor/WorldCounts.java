package sh.aki.tickcapacity.monitor;

import org.bukkit.Server;
import org.bukkit.World;

public record WorldCounts(int loadedChunks, int entities, int tickingBlockEntities) {

    public static WorldCounts of(Server server) {
        int chunks = 0;
        int entities = 0;
        int blockEntities = 0;
        for (World w : server.getWorlds()) {
            chunks += w.getChunkCount();
            entities += w.getEntityCount();
            blockEntities += w.getTickableTileEntityCount();
        }
        return new WorldCounts(chunks, entities, blockEntities);
    }
}

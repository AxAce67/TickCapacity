package sh.aki.tickcapacity.core.estimate;

import sh.aki.tickcapacity.core.model.TickSummary;

/** One measured benchmark stage. {@code run} is the repetition index. */
public record StageResult(
        int run,
        int virtualPlayers,
        TickSummary ticks,
        long durationMs,
        double processCpu,
        long gcTimeMs,
        long heapUsedMb,
        int loadedChunks,
        int entities,
        int newChunks
) {
}

package sh.aki.tickcapacity.core.model;

/**
 * One sample window. CPU values are fractions of the whole machine (NaN if unavailable);
 * {@code newChunks} counts chunks generated for the first time, -1 in files written before it existed.
 */
public record Sample(
        long epochMillis,
        long intervalMs,
        int players,
        TickSummary ticks,
        double tps,
        double processCpu,
        double systemCpu,
        double mainThreadCpuMs,
        long heapUsedMb,
        long heapMaxMb,
        long gcCount,
        long gcTimeMs,
        int loadedChunks,
        int entities,
        int tickingBlockEntities,
        int newChunks
) {

    public double newChunksPerSecond() {
        return newChunks < 0 || intervalMs <= 0 ? Double.NaN : newChunks * 1000.0 / intervalMs;
    }
}

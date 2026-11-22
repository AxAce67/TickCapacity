package sh.aki.tickcapacity.bench;

import sh.aki.tickcapacity.core.estimate.CapacityEstimate;
import sh.aki.tickcapacity.core.estimate.StageResult;
import sh.aki.tickcapacity.monitor.HardwareInfo;

import java.util.List;
import java.util.Map;

/** Stored as results/ID.json. */
public record BenchmarkResult(
        int formatVersion,
        String id,
        String pluginVersion,
        long startedAtEpochMs,
        long finishedAtEpochMs,
        Status status,
        String stopReason,
        int realPlayers,
        Parameters parameters,
        HardwareInfo hardware,
        List<StageResult> stages,
        CapacityEstimate estimate,
        CapacityEstimate calibratedEstimate
) {

    /** Version 1 files are still readable. */
    public static final int FORMAT_VERSION = 2;

    public CapacityEstimate bestEstimate() {
        return calibratedEstimate != null ? calibratedEstimate : estimate;
    }

    public enum Status {
        COMPLETED,
        LIMIT_REACHED,
        ABORTED,
        CANCELLED
    }

    public record Parameters(
            String worldName,
            long seed,
            int chunkRadius,
            int playersPerStage,
            int maxVirtualPlayers,
            int settleSeconds,
            int stageSeconds,
            Map<String, Integer> entitiesPerPlayer,
            boolean entityActivationRangeActive,
            int repetitions,
            double movementBlocksPerSecond
    ) {
    }
}

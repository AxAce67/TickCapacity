package sh.aki.tickcapacity.core.estimate;

import java.util.Optional;

/**
 * Real-player cost divided by virtual-player cost on this server; benchmark capacities are divided by it.
 * It mostly reflects how people play compared to the synthetic load, so it stays valid for later benchmarks,
 * e.g. after a hardware upgrade.
 */
public record Calibration(double factor, double relativeStdError, String benchmarkId, long createdAtEpochMs) {

    /** Outside this range the comparison is broken; players do not differ that much. */
    static final double MIN_FACTOR = 0.2;
    static final double MAX_FACTOR = 10;

    /** Needs an observed estimate with at least MEDIUM confidence. */
    public static Optional<Calibration> derive(CapacityEstimate observed, CapacityEstimate benchmark,
                                               String benchmarkId, long nowMs) {
        if (observed.source() != CapacityEstimate.Source.OBSERVED
                || benchmark.source() != CapacityEstimate.Source.BENCHMARK
                || observed.confidence().compareTo(Confidence.MEDIUM) < 0
                || benchmark.confidence() == Confidence.INSUFFICIENT
                || benchmark.isCalibrated()) {
            return Optional.empty();
        }
        double kObs = observed.perPlayerMs();
        double kBench = benchmark.perPlayerMs();
        if (!(kObs > 0) || !(kBench > 0)) {
            return Optional.empty();
        }
        double relObs = observed.perPlayerMsStdError() / kObs;
        double relBench = benchmark.perPlayerMsStdError() / kBench;
        if (!Double.isFinite(relObs) || !Double.isFinite(relBench) || relObs > 0.5 || relBench > 0.5) {
            return Optional.empty();
        }
        double factor = kObs / kBench;
        if (factor < MIN_FACTOR || factor > MAX_FACTOR) {
            return Optional.empty();
        }
        return Optional.of(new Calibration(factor, Math.hypot(relObs, relBench), benchmarkId, nowMs));
    }

    /** Converts to real players and widens the range by the factor's own uncertainty. */
    Range apply(Range virtual) {
        double z = 1.96 * relativeStdError;
        return new Range(virtual.point() / factor,
                virtual.low() / (factor * (1 + z)),
                virtual.high() / (factor * Math.max(0.05, 1 - z)));
    }
}

package sh.aki.tickcapacity.core.estimate;

/**
 * Targets for turning an MSPT model into player counts. "Comfortable" means mean and p95 MSPT both below
 * their thresholds; the limit is mean MSPT at {@code limitMspt} (50 ms = 20 TPS). {@code groupWeightCap}
 * keeps thousands of idle samples from outweighing busy periods.
 */
public record Thresholds(
        double limitMspt,
        double comfortableMspt,
        double comfortableP95Mspt,
        double confidence,
        int minSamples,
        int minSamplesPerGroup,
        int groupWeightCap
) {

    public static final Thresholds DEFAULT = new Thresholds(50.0, 30.0, 50.0, 0.95, 30, 3, 20);

    public Thresholds {
        if (!(comfortableMspt > 0 && comfortableMspt <= limitMspt)) {
            throw new IllegalArgumentException("comfortable MSPT must be in (0, limit]");
        }
        if (!(confidence > 0.5 && confidence < 1)) {
            throw new IllegalArgumentException("confidence must be in (0.5, 1)");
        }
        if (minSamplesPerGroup < 1 || minSamples < 1 || groupWeightCap < 1) {
            throw new IllegalArgumentException("sample minimums must be positive");
        }
    }
}

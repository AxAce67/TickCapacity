package sh.aki.tickcapacity.core.model;

import sh.aki.tickcapacity.core.stats.Stats;

import java.util.Arrays;

public record TickSummary(int ticks, double meanMs, double p50Ms, double p95Ms, double p99Ms, double maxMs,
                          double busyMs) {

    public static final TickSummary EMPTY = new TickSummary(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
            Double.NaN, 0);

    public static TickSummary of(double[] durationsMs) {
        int n = durationsMs.length;
        if (n == 0) {
            return EMPTY;
        }
        double[] sorted = Arrays.copyOf(durationsMs, n);
        Arrays.sort(sorted);
        double sum = Stats.sum(sorted, n);
        return new TickSummary(n, sum / n,
                Stats.percentileSorted(sorted, n, 0.50),
                Stats.percentileSorted(sorted, n, 0.95),
                Stats.percentileSorted(sorted, n, 0.99),
                sorted[n - 1],
                sum);
    }

    public boolean isEmpty() {
        return ticks == 0;
    }

    /** Capped at 20: a tick shorter than 50 ms still waits for the next one. */
    public double impliedTps() {
        if (isEmpty()) {
            return Double.NaN;
        }
        return Math.min(20.0, 1000.0 / Math.max(meanMs, 1e-9));
    }
}

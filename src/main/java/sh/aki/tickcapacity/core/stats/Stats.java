package sh.aki.tickcapacity.core.stats;

import java.util.Arrays;

public final class Stats {

    private Stats() {
    }

    public static double mean(double[] values, int length) {
        if (length == 0) {
            return Double.NaN;
        }
        double sum = 0;
        for (int i = 0; i < length; i++) {
            sum += values[i];
        }
        return sum / length;
    }

    public static double mean(double[] values) {
        return mean(values, values.length);
    }

    public static double sum(double[] values, int length) {
        double sum = 0;
        for (int i = 0; i < length; i++) {
            sum += values[i];
        }
        return sum;
    }

    /** Linear interpolation between closest ranks (R type 7, NumPy default). {@code p} is in [0, 1]. */
    public static double percentileSorted(double[] sorted, int length, double p) {
        if (length == 0) {
            return Double.NaN;
        }
        if (p <= 0) {
            return sorted[0];
        }
        if (p >= 1) {
            return sorted[length - 1];
        }
        double rank = p * (length - 1);
        int lo = (int) Math.floor(rank);
        int hi = Math.min(lo + 1, length - 1);
        double frac = rank - lo;
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac;
    }

    public static double percentile(double[] values, int length, double p) {
        double[] copy = Arrays.copyOf(values, length);
        Arrays.sort(copy);
        return percentileSorted(copy, length, p);
    }

    public static double median(double[] values) {
        return percentile(values, values.length, 0.5);
    }
}

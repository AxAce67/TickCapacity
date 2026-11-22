package sh.aki.tickcapacity.core.estimate;

/** Point estimate with interval. Infinity: no limit could be ruled out. NaN: unknown. */
public record Range(double point, double low, double high) {

    public static final Range UNKNOWN = new Range(Double.NaN, Double.NaN, Double.NaN);

    public boolean isKnown() {
        return !Double.isNaN(point);
    }

    /** The stricter of two criteria. */
    public static Range min(Range a, Range b) {
        return new Range(Math.min(a.point, b.point), Math.min(a.low, b.low), Math.min(a.high, b.high));
    }

    public Range shift(double offset) {
        return new Range(point + offset, low + offset, high + offset);
    }

    public double relativeWidth() {
        if (!isKnown() || point <= 0) {
            return Double.POSITIVE_INFINITY;
        }
        return (high - low) / point;
    }
}

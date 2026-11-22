package sh.aki.tickcapacity.core.estimate;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of an estimation. {@code perPlayerMs} is the straight-line slope even when a curved model was
 * used for the ranges. {@code points} and {@code curve} are chart data and are null in older result files.
 */
public record CapacityEstimate(
        Source source,
        Confidence confidence,
        Range recommended,
        Range limit,
        double perPlayerMs,
        double perPlayerMsStdError,
        double baseMs,
        double rSquared,
        int samples,
        int distinctLoadLevels,
        int maxObservedPlayers,
        int provenComfortablePlayers,
        List<Note> notes,
        boolean curved,
        double calibrationFactor,
        List<LoadPoint> points,
        List<CurvePoint> curve
) {

    public enum Source {
        OBSERVED,
        BENCHMARK
    }

    /** {@code key} is a message key suffix, {@code value} is optional (NaN). */
    public record Note(String key, double value) {
        public static Note of(String key) {
            return new Note(key, Double.NaN);
        }
    }

    public record LoadPoint(double players, double meanMs, double p95Ms, int samples) {
    }

    public record CurvePoint(double players, double meanMs, double lowerMs, double upperMs) {
    }

    public boolean isCalibrated() {
        return calibrationFactor > 0 && Double.isFinite(calibrationFactor);
    }

    public List<LoadPoint> pointsOrEmpty() {
        return points == null ? List.of() : points;
    }

    public List<CurvePoint> curveOrEmpty() {
        return curve == null ? List.of() : curve;
    }

    public List<Note> notesOrEmpty() {
        return notes == null ? List.of() : notes;
    }

    public CapacityEstimate withNote(Note note) {
        List<Note> all = new ArrayList<>(notesOrEmpty());
        all.add(note);
        return new CapacityEstimate(source, confidence, recommended, limit, perPlayerMs, perPlayerMsStdError, baseMs,
                rSquared, samples, distinctLoadLevels, maxObservedPlayers, provenComfortablePlayers, List.copyOf(all),
                curved, calibrationFactor, points, curve);
    }

    public static CapacityEstimate insufficient(Source source, int samples, int levels, int maxObserved,
                                                int proven, List<Note> notes, List<LoadPoint> points) {
        return new CapacityEstimate(source, Confidence.INSUFFICIENT, Range.UNKNOWN, Range.UNKNOWN,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, samples, levels, maxObserved, proven,
                List.copyOf(notes), false, 0, List.copyOf(points), List.of());
    }
}

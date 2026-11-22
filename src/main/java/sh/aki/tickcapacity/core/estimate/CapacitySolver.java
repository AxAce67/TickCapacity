package sh.aki.tickcapacity.core.estimate;

import sh.aki.tickcapacity.core.stats.FittedModel;
import sh.aki.tickcapacity.core.stats.LinearFit;

import java.util.ArrayList;
import java.util.List;

/** Finds where an MSPT model and its confidence band reach a threshold. */
public final class CapacitySolver {

    /** Beyond this a capacity is reported as unbounded. */
    public static final double MAX_PLAYERS = 2000;
    private static final double STEP = 0.05;

    private CapacitySolver() {
    }

    /** low/high come from the upper/lower band; unreachable values are +Infinity. */
    public static Range solve(FittedModel model, double threshold, double tCritical) {
        double point;
        if (model instanceof LinearFit fit) {
            if (fit.slope() > 0) {
                point = Math.max(0, (threshold - fit.intercept()) / fit.slope());
                if (point > MAX_PLAYERS) {
                    point = Double.POSITIVE_INFINITY;
                }
            } else {
                point = fit.intercept() >= threshold ? 0 : Double.POSITIVE_INFINITY;
            }
        } else {
            point = firstCrossing(model, threshold, 0);
        }
        double low = firstCrossing(model, threshold, tCritical);
        double high = firstCrossing(model, threshold, -tCritical);
        low = Math.min(low, point);
        high = Math.max(high, point);
        return new Range(point, low, high);
    }

    /** Crossing of the model itself, without the band. */
    public static double point(FittedModel model, double threshold) {
        if (model instanceof LinearFit fit) {
            if (fit.slope() > 0) {
                double p = Math.max(0, (threshold - fit.intercept()) / fit.slope());
                return p > MAX_PLAYERS ? Double.POSITIVE_INFINITY : p;
            }
            return fit.intercept() >= threshold ? 0 : Double.POSITIVE_INFINITY;
        }
        return firstCrossing(model, threshold, 0);
    }

    private static double firstCrossing(FittedModel model, double threshold, double t) {
        for (double n = 0; n <= MAX_PLAYERS; n += STEP) {
            double v = model.predict(n) + t * model.meanStandardError(n);
            if (v >= threshold) {
                return n;
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    /** @return the interpolated crossing, or NaN if the data never reaches the threshold */
    public static double interpolatedCrossing(double[] x, double[] y, double threshold) {
        for (int i = 0; i < x.length; i++) {
            // A later stage clearly back below the threshold means this one was a spike (e.g. chunk loading).
            boolean confirmed = true;
            for (int j = i + 1; j < x.length; j++) {
                if (y[j] < threshold * 0.9) {
                    confirmed = false;
                    break;
                }
            }
            if (y[i] >= threshold && confirmed) {
                if (i == 0) {
                    return x[0];
                }
                double dy = y[i] - y[i - 1];
                if (dy <= 0) {
                    return x[i];
                }
                return x[i - 1] + (threshold - y[i - 1]) / dy * (x[i] - x[i - 1]);
            }
        }
        return Double.NaN;
    }

    /** Chart data: the model and its band from 0 to {@code maxX}. */
    public static List<CapacityEstimate.CurvePoint> curve(FittedModel model, double tCritical, double maxX,
                                                          java.util.function.DoubleUnaryOperator toPlayers) {
        List<CapacityEstimate.CurvePoint> out = new ArrayList<>();
        int steps = 40;
        for (int i = 0; i <= steps; i++) {
            double x = maxX * i / steps;
            out.add(new CapacityEstimate.CurvePoint(toPlayers.applyAsDouble(x), model.predict(x),
                    model.lowerBand(x, tCritical), model.upperBand(x, tCritical)));
        }
        return List.copyOf(out);
    }

    static double chartMax(double maxMeasured, Range limit) {
        double m = Math.max(maxMeasured * 1.2, 5);
        if (Double.isFinite(limit.point())) {
            m = Math.max(m, limit.point() * 1.1);
        }
        return Math.min(m, MAX_PLAYERS);
    }
}

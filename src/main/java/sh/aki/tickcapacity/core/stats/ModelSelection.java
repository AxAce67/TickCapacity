package sh.aki.tickcapacity.core.stats;

/**
 * Straight line or upward-curving quadratic, chosen by AICc. The quadratic has to curve upward and win by
 * at least 2, so the line stays the default.
 */
public final class ModelSelection {

    public static final int MIN_POINTS_FOR_CURVE = 6;

    /** {@code linear} is always available; its slope is reported as the cost per player. */
    public record Choice(FittedModel model, LinearFit linear, boolean curved) {
    }

    private ModelSelection() {
    }

    public static Choice select(double[] x, double[] y, double[] w) {
        LinearFit linear = LinearFit.fit(x, y, w);
        int n = x.length;
        if (n < MIN_POINTS_FOR_CURVE) {
            return new Choice(linear, linear, false);
        }
        QuadraticFit quad;
        try {
            quad = QuadraticFit.fit(x, y, w);
        } catch (IllegalArgumentException e) {
            return new Choice(linear, linear, false);
        }
        double linearSse = linear.residualVariance() * (n - 2);
        if (quad.curvature() > 0 && aicc(quad.sse(), n, 3) < aicc(linearSse, n, 2) - 2) {
            return new Choice(quad, linear, true);
        }
        return new Choice(linear, linear, false);
    }

    static double aicc(double sse, int n, int p) {
        int k = p + 1;
        return n * Math.log(Math.max(sse, 1e-12) / n) + 2.0 * k + 2.0 * k * (k + 1) / Math.max(1, n - k - 1);
    }
}

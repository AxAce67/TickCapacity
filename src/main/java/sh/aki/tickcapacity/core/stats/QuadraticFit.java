package sh.aki.tickcapacity.core.stats;

/**
 * Weighted least squares parabola, for servers whose MSPT rises faster than linearly near saturation
 * (GC pressure, cache misses).
 */
public final class QuadraticFit implements FittedModel {

    private final double[] beta;
    /** (X^T W X)^-1 */
    private final double[][] inverse;
    private final double residualVariance;
    private final double sse;
    private final double rSquared;
    private final int points;

    private QuadraticFit(double[] beta, double[][] inverse, double residualVariance, double sse, double rSquared,
                         int points) {
        this.beta = beta;
        this.inverse = inverse;
        this.residualVariance = residualVariance;
        this.sse = sse;
        this.rSquared = rSquared;
        this.points = points;
    }

    public static QuadraticFit fit(double[] x, double[] y, double[] w) {
        int n = x.length;
        if (y.length != n || w.length != n) {
            throw new IllegalArgumentException("array lengths differ");
        }
        if (n < 4) {
            throw new IllegalArgumentException("need at least 4 points, got " + n);
        }
        double[][] a = new double[3][3];
        double[] b = new double[3];
        double sw = 0;
        double swy = 0;
        for (int i = 0; i < n; i++) {
            double[] v = {1, x[i], x[i] * x[i]};
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    a[r][c] += w[i] * v[r] * v[c];
                }
                b[r] += w[i] * v[r] * y[i];
            }
            sw += w[i];
            swy += w[i] * y[i];
        }
        double[][] inv = invert3(a);
        double[] beta = new double[3];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                beta[r] += inv[r][c] * b[c];
            }
        }
        double ybar = swy / sw;
        double sse = 0;
        double syy = 0;
        for (int i = 0; i < n; i++) {
            double pred = beta[0] + beta[1] * x[i] + beta[2] * x[i] * x[i];
            sse += w[i] * (y[i] - pred) * (y[i] - pred);
            syy += w[i] * (y[i] - ybar) * (y[i] - ybar);
        }
        return new QuadraticFit(beta, inv, sse / (n - 3), sse, syy > 0 ? 1 - sse / syy : 1, n);
    }

    private static double[][] invert3(double[][] m) {
        double a = m[0][0], b = m[0][1], c = m[0][2];
        double d = m[1][0], e = m[1][1], f = m[1][2];
        double g = m[2][0], h = m[2][1], k = m[2][2];
        double det = a * (e * k - f * h) - b * (d * k - f * g) + c * (d * h - e * g);
        double scale = Math.abs(a * e * k) + 1e-300;
        if (Math.abs(det) <= 1e-12 * scale) {
            throw new IllegalArgumentException("x values have too little spread for a quadratic fit");
        }
        return new double[][]{
                {(e * k - f * h) / det, (c * h - b * k) / det, (b * f - c * e) / det},
                {(f * g - d * k) / det, (a * k - c * g) / det, (c * d - a * f) / det},
                {(d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det}};
    }

    @Override
    public double predict(double x) {
        return beta[0] + beta[1] * x + beta[2] * x * x;
    }

    @Override
    public double meanStandardError(double x) {
        double[] v = {1, x, x * x};
        double q = 0;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                q += v[r] * inverse[r][c] * v[c];
            }
        }
        return Math.sqrt(Math.max(0, residualVariance * q));
    }

    @Override
    public int degreesOfFreedom() {
        return points - 3;
    }

    @Override
    public double rSquared() {
        return rSquared;
    }

    public double curvature() {
        return beta[2];
    }

    public double sse() {
        return sse;
    }

    public int points() {
        return points;
    }
}

package sh.aki.tickcapacity.core.stats;

/**
 * Weighted least squares line. Weights are relative inverse variances and sigma^2 comes from the weighted
 * residuals, so the confidence band reflects the actual scatter.
 */
public record LinearFit(
        double intercept,
        double slope,
        double residualVariance,
        double weightSum,
        double weightedMeanX,
        double sxx,
        double rSquared,
        int points
) implements FittedModel {

    public static LinearFit fit(double[] x, double[] y, double[] w) {
        int n = x.length;
        if (y.length != n || w.length != n) {
            throw new IllegalArgumentException("array lengths differ");
        }
        if (n < 3) {
            throw new IllegalArgumentException("need at least 3 points, got " + n);
        }
        double sw = 0;
        double swx = 0;
        double swy = 0;
        for (int i = 0; i < n; i++) {
            if (!(w[i] > 0)) {
                throw new IllegalArgumentException("weights must be positive");
            }
            sw += w[i];
            swx += w[i] * x[i];
            swy += w[i] * y[i];
        }
        double xbar = swx / sw;
        double ybar = swy / sw;
        double sxx = 0;
        double sxy = 0;
        double syy = 0;
        for (int i = 0; i < n; i++) {
            double dx = x[i] - xbar;
            double dy = y[i] - ybar;
            sxx += w[i] * dx * dx;
            sxy += w[i] * dx * dy;
            syy += w[i] * dy * dy;
        }
        if (sxx <= 0) {
            throw new IllegalArgumentException("x values have no spread");
        }
        double slope = sxy / sxx;
        double intercept = ybar - slope * xbar;
        double sse = 0;
        for (int i = 0; i < n; i++) {
            double r = y[i] - (intercept + slope * x[i]);
            sse += w[i] * r * r;
        }
        double residualVariance = sse / (n - 2);
        double rSquared = syy > 0 ? 1 - sse / syy : 1;
        return new LinearFit(intercept, slope, residualVariance, sw, xbar, sxx, rSquared, n);
    }

    public static LinearFit fit(double[] x, double[] y) {
        double[] w = new double[x.length];
        java.util.Arrays.fill(w, 1);
        return fit(x, y, w);
    }

    @Override
    public int degreesOfFreedom() {
        return points - 2;
    }

    @Override
    public double predict(double x) {
        return intercept + slope * x;
    }

    public double slopeStandardError() {
        return Math.sqrt(residualVariance / sxx);
    }

    /** Infinite when the fit is exact. */
    public double slopeTStatistic() {
        double se = slopeStandardError();
        if (se == 0) {
            return slope == 0 ? 0 : Math.copySign(Double.POSITIVE_INFINITY, slope);
        }
        return slope / se;
    }

    @Override
    public double meanStandardError(double x) {
        double dx = x - weightedMeanX;
        return Math.sqrt(residualVariance * (1 / weightSum + dx * dx / sxx));
    }

    @Override
    public double lowerBand(double x, double tCritical) {
        return predict(x) - tCritical * meanStandardError(x);
    }

    @Override
    public double upperBand(double x, double tCritical) {
        return predict(x) + tCritical * meanStandardError(x);
    }
}

package sh.aki.tickcapacity.core.stats;

import java.util.Arrays;

/** Multiple linear regression with Huber weights (IRLS), so spikes from autosaves or GC do not dominate. */
public final class RobustRegression {

    /** 95% efficiency under normal errors. */
    private static final double HUBER_K = 1.345;
    private static final int MAX_ITERATIONS = 30;

    private final double[] beta;
    private final double[] standardErrors;
    private final double scale;
    private final int n;

    private RobustRegression(double[] beta, double[] standardErrors, double scale, int n) {
        this.beta = beta;
        this.standardErrors = standardErrors;
        this.scale = scale;
        this.n = n;
    }

    /** @param x one row of predictors per observation, without the intercept column */
    public static RobustRegression fit(double[][] x, double[] y) {
        int n = y.length;
        int p = x[0].length + 1;
        if (n < p + 2) {
            throw new IllegalArgumentException("need at least " + (p + 2) + " rows");
        }
        double[] w = new double[n];
        Arrays.fill(w, 1);
        double[] beta = weightedLeastSquares(x, y, w);
        double scale = 1;
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            double[] residuals = residuals(x, y, beta);
            scale = madScale(residuals);
            if (scale <= 1e-12) {
                break;
            }
            for (int i = 0; i < n; i++) {
                double u = Math.abs(residuals[i]) / (HUBER_K * scale);
                w[i] = u <= 1 ? 1 : 1 / u;
            }
            double[] next = weightedLeastSquares(x, y, w);
            double change = 0;
            for (int j = 0; j < p; j++) {
                change = Math.max(change, Math.abs(next[j] - beta[j]));
            }
            beta = next;
            if (change < 1e-9) {
                break;
            }
        }
        double[][] xtwx = normalMatrix(x, w);
        double[][] inv = invert(xtwx);
        double[] r = residuals(x, y, beta);
        double sse = 0;
        double sw = 0;
        for (int i = 0; i < n; i++) {
            sse += w[i] * r[i] * r[i];
            sw += w[i];
        }
        double s2 = sse / Math.max(1, n - p) * (n / sw);
        double[] se = new double[p];
        for (int j = 0; j < p; j++) {
            se[j] = Math.sqrt(Math.max(0, s2 * inv[j][j]));
        }
        return new RobustRegression(beta, se, scale, n);
    }

    public double coefficient(int index) {
        return beta[index];
    }

    public double standardError(int index) {
        return standardErrors[index];
    }

    public double predict(double... predictors) {
        double v = beta[0];
        for (int j = 0; j < predictors.length; j++) {
            v += beta[j + 1] * predictors[j];
        }
        return v;
    }

    /** MAD-based estimate of the residual standard deviation. */
    public double scale() {
        return scale;
    }

    public int rows() {
        return n;
    }

    private static double[] weightedLeastSquares(double[][] x, double[] y, double[] w) {
        int p = x[0].length + 1;
        double[][] a = normalMatrix(x, w);
        double[] b = new double[p];
        for (int i = 0; i < y.length; i++) {
            b[0] += w[i] * y[i];
            for (int j = 1; j < p; j++) {
                b[j] += w[i] * x[i][j - 1] * y[i];
            }
        }
        double[][] inv = invert(a);
        double[] beta = new double[p];
        for (int r = 0; r < p; r++) {
            for (int c = 0; c < p; c++) {
                beta[r] += inv[r][c] * b[c];
            }
        }
        return beta;
    }

    private static double[][] normalMatrix(double[][] x, double[] w) {
        int p = x[0].length + 1;
        double[][] a = new double[p][p];
        double[] row = new double[p];
        for (int i = 0; i < x.length; i++) {
            row[0] = 1;
            System.arraycopy(x[i], 0, row, 1, p - 1);
            for (int r = 0; r < p; r++) {
                for (int c = 0; c < p; c++) {
                    a[r][c] += w[i] * row[r] * row[c];
                }
            }
        }
        return a;
    }

    private static double[] residuals(double[][] x, double[] y, double[] beta) {
        double[] r = new double[y.length];
        for (int i = 0; i < y.length; i++) {
            double pred = beta[0];
            for (int j = 1; j < beta.length; j++) {
                pred += beta[j] * x[i][j - 1];
            }
            r[i] = y[i] - pred;
        }
        return r;
    }

    private static double madScale(double[] residuals) {
        double[] abs = new double[residuals.length];
        double med = Stats.median(residuals);
        for (int i = 0; i < residuals.length; i++) {
            abs[i] = Math.abs(residuals[i] - med);
        }
        return 1.4826 * Stats.median(abs);
    }

    static double[][] invert(double[][] m) {
        int p = m.length;
        double[][] a = new double[p][2 * p];
        double maxAbs = 0;
        for (int i = 0; i < p; i++) {
            System.arraycopy(m[i], 0, a[i], 0, p);
            a[i][p + i] = 1;
            for (int j = 0; j < p; j++) {
                maxAbs = Math.max(maxAbs, Math.abs(m[i][j]));
            }
        }
        for (int col = 0; col < p; col++) {
            int pivot = col;
            for (int r = col + 1; r < p; r++) {
                if (Math.abs(a[r][col]) > Math.abs(a[pivot][col])) {
                    pivot = r;
                }
            }
            if (Math.abs(a[pivot][col]) <= 1e-12 * Math.max(1, maxAbs)) {
                throw new IllegalArgumentException("predictors are collinear");
            }
            double[] tmp = a[col];
            a[col] = a[pivot];
            a[pivot] = tmp;
            double div = a[col][col];
            for (int j = 0; j < 2 * p; j++) {
                a[col][j] /= div;
            }
            for (int r = 0; r < p; r++) {
                if (r != col && a[r][col] != 0) {
                    double factor = a[r][col];
                    for (int j = 0; j < 2 * p; j++) {
                        a[r][j] -= factor * a[col][j];
                    }
                }
            }
        }
        double[][] inv = new double[p][p];
        for (int i = 0; i < p; i++) {
            System.arraycopy(a[i], p, inv[i], 0, p);
        }
        return inv;
    }
}

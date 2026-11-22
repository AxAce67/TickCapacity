package sh.aki.tickcapacity.core.stats;

public final class Distributions {

    private Distributions() {
    }

    /** Inverse normal CDF (Acklam, relative error below 1.2e-9). */
    public static double normalQuantile(double p) {
        if (!(p > 0 && p < 1)) {
            throw new IllegalArgumentException("p must be in (0, 1): " + p);
        }
        final double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02,
                1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        final double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02,
                6.680131188771972e+01, -1.328068155288572e+01};
        final double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00,
                -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        final double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00,
                3.754408661907416e+00};
        final double pLow = 0.02425;
        final double pHigh = 1 - pLow;
        if (p < pLow) {
            double q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                    / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        if (p <= pHigh) {
            double q = p - 0.5;
            double r = q * q;
            return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q
                    / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
        }
        double q = Math.sqrt(-2 * Math.log(1 - p));
        return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5])
                / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
    }

    /**
     * Two-sided t critical value: P(|T| > t) = 1 - confidence. Hill's algorithm 396, about 5 significant
     * digits.
     */
    public static double tCritical(double confidence, double df) {
        if (!(confidence > 0 && confidence < 1)) {
            throw new IllegalArgumentException("confidence must be in (0, 1): " + confidence);
        }
        if (df < 1) {
            throw new IllegalArgumentException("df must be >= 1: " + df);
        }
        double p = 1 - confidence; // two-tailed probability
        double n = df;
        if (n == 1) {
            double x = p * Math.PI / 2;
            return Math.cos(x) / Math.sin(x);
        }
        if (n == 2) {
            return Math.sqrt(2 / (p * (2 - p)) - 2);
        }
        double a = 1 / (n - 0.5);
        double b = 48 / (a * a);
        double c = ((20700 * a / b - 98) * a - 16) * a + 96.36;
        double d = ((94.5 / (b + c) - 3) / b + 1) * Math.sqrt(a * Math.PI / 2) * n;
        double x = d * p;
        double y = Math.pow(x, 2 / n);
        if (y > 0.05 + a) {
            x = normalQuantile(0.5 * p);
            y = x * x;
            if (n < 5) {
                c += 0.3 * (n - 4.5) * (x + 0.6);
            }
            c = (((0.05 * d * x - 5) * x - 7) * x - 2) * x + b + c;
            y = (((((0.4 * y + 6.3) * y + 36) * y + 94.5) / c - y - 3) / b + 1) * x;
            y = a * y * y;
            y = y > 0.002 ? Math.exp(y) - 1 : 0.5 * y * y + y;
        } else {
            y = ((1 / (((n + 6) / (n * y) - 0.089 * d - 0.822) * (n + 2) * 3) + 0.5 / (n + 4)) * y - 1)
                    * (n + 1) / (n + 2) + 1 / y;
        }
        return Math.sqrt(n * y);
    }
}

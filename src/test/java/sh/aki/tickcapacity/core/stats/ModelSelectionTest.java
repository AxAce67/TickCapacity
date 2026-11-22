package sh.aki.tickcapacity.core.stats;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelSelectionTest {

    private static double[] ones(int n) {
        double[] w = new double[n];
        Arrays.fill(w, 1);
        return w;
    }

    @Test
    void quadraticFitRecoversCoefficients() {
        double[] x = {0, 1, 2, 3, 4, 5, 6};
        double[] y = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            y[i] = 2 + 0.5 * x[i] + 0.25 * x[i] * x[i];
        }
        QuadraticFit q = QuadraticFit.fit(x, y, ones(x.length));
        assertEquals(0.25, q.curvature(), 1e-9);
        assertEquals(2 + 0.5 * 10 + 25, q.predict(10), 1e-6);
        assertEquals(0, q.meanStandardError(3), 1e-6);
    }

    @Test
    void straightLineStaysLinear() {
        Random rnd = new Random(7);
        double[] x = new double[20];
        double[] y = new double[20];
        for (int i = 0; i < 20; i++) {
            x[i] = i * 2;
            y[i] = 5 + 1.0 * x[i] + rnd.nextGaussian() * 0.5;
        }
        assertFalse(ModelSelection.select(x, y, ones(20)).curved());
    }

    @Test
    void clearCurvatureIsDetected() {
        Random rnd = new Random(8);
        double[] x = new double[20];
        double[] y = new double[20];
        for (int i = 0; i < 20; i++) {
            x[i] = i * 2;
            y[i] = 5 + 0.5 * x[i] + 0.02 * x[i] * x[i] + rnd.nextGaussian() * 0.3;
        }
        ModelSelection.Choice c = ModelSelection.select(x, y, ones(20));
        assertTrue(c.curved());
        // Near the end, the curve should predict much better than the line.
        double truth = 5 + 0.5 * 40 + 0.02 * 1600;
        assertTrue(Math.abs(c.model().predict(40) - truth) < Math.abs(c.linear().predict(40) - truth));
    }

    @Test
    void tooFewPointsNeverCurve() {
        double[] x = {0, 1, 2, 3, 4};
        double[] y = {0, 1, 4, 9, 16};
        assertFalse(ModelSelection.select(x, y, ones(5)).curved());
    }

    @Test
    void robustRegressionIgnoresOutliers() {
        Random rnd = new Random(9);
        int n = 200;
        double[][] x = new double[n][];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            double a = rnd.nextInt(20);
            double b = rnd.nextDouble() * 10;
            x[i] = new double[]{a, b};
            y[i] = 4 + 0.8 * a + 0.3 * b + rnd.nextGaussian() * 0.5 + (i % 25 == 0 ? 60 : 0);
        }
        RobustRegression r = RobustRegression.fit(x, y);
        assertEquals(4, r.coefficient(0), 0.4);
        assertEquals(0.8, r.coefficient(1), 0.05);
        assertEquals(0.3, r.coefficient(2), 0.08);
    }
}

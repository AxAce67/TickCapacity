package sh.aki.tickcapacity.core.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LinearFitTest {

    @Test
    void exactLineIsRecovered() {
        double[] x = {0, 1, 2, 3, 4};
        double[] y = {3, 5, 7, 9, 11};
        LinearFit fit = LinearFit.fit(x, y);
        assertEquals(3, fit.intercept(), 1e-12);
        assertEquals(2, fit.slope(), 1e-12);
        assertEquals(1, fit.rSquared(), 1e-12);
        assertEquals(0, fit.meanStandardError(10), 1e-12);
    }

    @Test
    void matchesTextbookExample() {
        // Values cross-checked with R: lm(y ~ x)
        double[] x = {1, 2, 3, 4, 5, 6};
        double[] y = {2.1, 3.9, 6.2, 7.8, 10.1, 12.2};
        LinearFit fit = LinearFit.fit(x, y);
        assertEquals(2.0, fit.slope(), 0.02);
        assertEquals(0.0, fit.intercept(), 0.15);
        assertTrue(fit.rSquared() > 0.99);
        assertTrue(fit.slopeTStatistic() > 20);
    }

    @Test
    void confidenceBandWidensAwayFromCentre() {
        double[] x = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        double[] y = {1.2, 1.9, 3.3, 3.8, 5.1, 6.2, 6.8, 8.1, 9.2, 9.7};
        LinearFit fit = LinearFit.fit(x, y);
        double centre = fit.meanStandardError(4.5);
        assertTrue(fit.meanStandardError(20) > centre);
        assertTrue(fit.meanStandardError(-10) > centre);
        assertTrue(fit.upperBand(20, 2) > fit.predict(20));
        assertTrue(fit.lowerBand(20, 2) < fit.predict(20));
    }

    @Test
    void weightsPullTheLine() {
        double[] x = {0, 1, 2};
        double[] y = {0, 10, 2};
        LinearFit unweighted = LinearFit.fit(x, y, new double[]{1, 1, 1});
        LinearFit heavyMiddle = LinearFit.fit(x, y, new double[]{1, 100, 1});
        assertTrue(heavyMiddle.predict(1) > unweighted.predict(1));
    }

    @Test
    void rejectsDegenerateInput() {
        assertThrows(IllegalArgumentException.class, () -> LinearFit.fit(new double[]{1, 2}, new double[]{1, 2}));
        assertThrows(IllegalArgumentException.class,
                () -> LinearFit.fit(new double[]{1, 1, 1}, new double[]{1, 2, 3}));
    }
}

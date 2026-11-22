package sh.aki.tickcapacity.core.stats;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DistributionsTest {

    @ParameterizedTest
    @CsvSource({
            "0.975, 1.959964",
            "0.95, 1.644854",
            "0.5, 0.0",
            "0.01, -2.326348",
            "0.999, 3.090232"
    })
    void normalQuantileMatchesTables(double p, double expected) {
        assertEquals(expected, Distributions.normalQuantile(p), 1e-5);
    }

    /** Reference values from standard t tables. */
    @ParameterizedTest
    @CsvSource({
            "0.95, 1, 12.7062",
            "0.95, 2, 4.3027",
            "0.95, 3, 3.1824",
            "0.95, 5, 2.5706",
            "0.95, 10, 2.2281",
            "0.95, 30, 2.0423",
            "0.95, 120, 1.9799",
            "0.99, 20, 2.8453",
            "0.90, 8, 1.8595"
    })
    void tCriticalMatchesTables(double confidence, double df, double expected) {
        assertEquals(expected, Distributions.tCritical(confidence, df), 1e-3);
    }
}

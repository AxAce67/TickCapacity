package sh.aki.tickcapacity.core.estimate;

import org.junit.jupiter.api.Test;
import sh.aki.tickcapacity.core.model.Sample;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservedCapacityEstimatorTest {

    private final ObservedCapacityEstimator estimator = new ObservedCapacityEstimator(Thresholds.DEFAULT);

    /** MSPT = 6 + 1.2 * players with noise and occasional spikes; players observed 0..20. */
    private static List<Sample> linearServer(int maxPlayers, long seed) {
        Random rnd = new Random(seed);
        List<Sample> samples = new ArrayList<>();
        for (int p = 0; p <= maxPlayers; p++) {
            for (int i = 0; i < 15; i++) {
                double mean = 6 + 1.2 * p + rnd.nextGaussian() * 0.8;
                if (rnd.nextInt(20) == 0) {
                    mean += 25; // spike sample (autosave, GC)
                }
                samples.add(TestData.sample(p, mean, mean * 1.4));
            }
        }
        return samples;
    }

    @Test
    void recoversCapacityOfLinearServer() {
        CapacityEstimate e = estimator.estimate(linearServer(20, 1));
        // True values: comfortable mean 30 -> 20 players, p95 = 1.4*mean <= 50 -> mean <= 35.7 -> 24.8
        // so recommended = 20; limit 50 -> 36.7
        assertEquals(20, e.recommended().point(), 1.5);
        assertEquals(36.7, e.limit().point(), 2.5);
        assertEquals(1.2, e.perPlayerMs(), 0.1);
        assertTrue(e.recommended().low() <= e.recommended().point());
        assertTrue(e.recommended().high() >= e.recommended().point());
        assertEquals(Confidence.HIGH, e.confidence());
        assertEquals(20, e.maxObservedPlayers());
    }

    @Test
    void extrapolationLowersConfidenceAndWidensInterval() {
        CapacityEstimate near = estimator.estimate(linearServer(20, 2));
        CapacityEstimate far = estimator.estimate(linearServer(5, 2));
        assertTrue(far.confidence().compareTo(near.confidence()) < 0,
                "far extrapolation should be less confident: " + far.confidence());
        assertTrue(far.recommended().relativeWidth() > near.recommended().relativeWidth());
        assertTrue(far.notes().stream().anyMatch(n -> n.key().equals("extrapolated")));
    }

    /** A 95% interval must contain the true capacity in roughly 95% of repeated experiments. */
    @Test
    void confidenceIntervalHasNominalCoverage() {
        int runs = 400;
        int coveredRecommended = 0;
        int coveredLimit = 0;
        for (int seed = 0; seed < runs; seed++) {
            CapacityEstimate e = estimator.estimate(linearServer(8, 1000 + seed));
            if (e.recommended().low() <= 20 && e.recommended().high() >= 20) {
                coveredRecommended++;
            }
            if (e.limit().low() <= 44 / 1.2 && e.limit().high() >= 44 / 1.2) {
                coveredLimit++;
            }
        }
        assertTrue(coveredRecommended / (double) runs >= 0.90, "recommended coverage " + coveredRecommended);
        assertTrue(coveredLimit / (double) runs >= 0.90, "limit coverage " + coveredLimit);
    }

    @Test
    void insufficientDataGivesNoNumbersButProvenBound() {
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            samples.add(TestData.sample(3, 12, 18));
            samples.add(TestData.sample(4, 13, 19));
        }
        CapacityEstimate e = estimator.estimate(samples);
        assertEquals(Confidence.INSUFFICIENT, e.confidence());
        assertTrue(Double.isNaN(e.recommended().point()));
        assertEquals(4, e.provenComfortablePlayers());
        assertTrue(e.notes().stream().anyMatch(n -> n.key().equals("too-few-player-counts")));
    }

    @Test
    void flatLoadIsReportedAsLowConfidence() {
        Random rnd = new Random(3);
        List<Sample> samples = new ArrayList<>();
        for (int p = 0; p <= 6; p++) {
            for (int i = 0; i < 10; i++) {
                double mean = 8 + rnd.nextGaussian();
                samples.add(TestData.sample(p, mean, mean * 1.3));
            }
        }
        CapacityEstimate e = estimator.estimate(samples);
        assertEquals(Confidence.LOW, e.confidence());
        assertTrue(e.notes().stream().anyMatch(n -> n.key().equals("no-clear-load-trend")));
        assertEquals(6, e.provenComfortablePlayers());
    }

    @Test
    void sparseGroupsAreIgnored() {
        List<Sample> samples = new ArrayList<>(linearServer(10, 4));
        samples.add(TestData.sample(50, 500, 600)); // a single odd sample must not drive the fit
        CapacityEstimate e = estimator.estimate(samples);
        assertEquals(10, e.maxObservedPlayers());
        assertEquals(1.2, e.perPlayerMs(), 0.15);
    }

    @Test
    void pausedSamplesAreIgnored() {
        List<Sample> samples = new ArrayList<>(linearServer(10, 5));
        CapacityEstimate before = estimator.estimate(samples);
        for (int i = 0; i < 50; i++) {
            Sample s = TestData.sample(0, 400, 400);
            samples.add(new Sample(s.epochMillis(), s.intervalMs(), 0,
                    new sh.aki.tickcapacity.core.model.TickSummary(2, 400, 400, 400, 400, 400, 800),
                    1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        }
        assertEquals(before.perPlayerMs(), estimator.estimate(samples).perPlayerMs(), 1e-9);
    }
}

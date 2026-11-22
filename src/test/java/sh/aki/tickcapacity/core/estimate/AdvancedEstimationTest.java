package sh.aki.tickcapacity.core.estimate;

import org.junit.jupiter.api.Test;
import sh.aki.tickcapacity.core.model.Sample;
import sh.aki.tickcapacity.core.model.TickSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdvancedEstimationTest {

    private static StageResult stage(int run, int v, double mean) {
        return new StageResult(run, v, new TickSummary(600, mean, mean, mean * 1.2, mean * 1.3, mean * 1.5,
                mean * 600), 30_000, 0.3, 10, 1000, 1000, 500, 0);
    }

    @Test
    void curvedServerGivesLessOptimisticLimit() {
        // MSPT = 4 + 0.6 n + 0.012 n^2, observed up to 30 players. True limit (50 ms) at n = 41.8
        Random rnd = new Random(11);
        List<Sample> samples = new ArrayList<>();
        for (int p = 0; p <= 30; p++) {
            for (int i = 0; i < 10; i++) {
                double m = 4 + 0.6 * p + 0.012 * p * p + rnd.nextGaussian() * 0.6;
                samples.add(TestData.sample(p, m, m * 1.3));
            }
        }
        CapacityEstimate e = new ObservedCapacityEstimator(Thresholds.DEFAULT).estimate(samples);
        assertTrue(e.curved());
        assertTrue(e.notes().stream().anyMatch(n -> n.key().equals("curved-model")));
        assertEquals(41.8, e.limit().point(), 3);
        assertFalse(e.curveOrEmpty().isEmpty());
        assertEquals(31, e.pointsOrEmpty().size());
    }

    @Test
    void autocorrelatedDataGetsWiderIntervalThanNaiveRegression() {
        // Each hour has its own random offset (e.g. what players happen to do), shared by all its samples.
        Random rnd = new Random(21);
        List<Sample> samples = new ArrayList<>();
        long t = 1_700_000_000_000L;
        for (int hour = 0; hour < 24 * 5; hour++) {
            int players = (hour % 24) * 20 / 24;
            double hourEffect = rnd.nextGaussian() * 2.5;
            for (int i = 0; i < 120; i++) {
                double m = 5 + 1.0 * players + hourEffect + rnd.nextGaussian() * 0.5;
                TickSummary ts = new TickSummary(200, m, m, m * 1.3, m * 1.4, m * 1.6, m * 200);
                samples.add(new Sample(t, 30_000, players, ts, 20, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
                t += 30_000;
            }
        }
        CapacityEstimate e = new ObservedCapacityEstimator(Thresholds.DEFAULT).estimate(samples);
        // The naive interval from ~20 near-perfect medians would be a fraction of a player wide.
        assertTrue(e.recommended().high() - e.recommended().low() >= 1.0, "interval " + e.recommended());
        assertTrue(e.recommended().low() <= 25 && e.recommended().high() >= 25, "interval " + e.recommended());
    }

    @Test
    void shortHistoryCapsConfidence() {
        Random rnd = new Random(22);
        List<Sample> samples = new ArrayList<>();
        long t = 1_700_000_000_000L;
        for (int i = 0; i < 2000; i++) {
            int p = i % 21;
            double m = 6 + 1.2 * p + rnd.nextGaussian() * 0.5;
            TickSummary ts = new TickSummary(200, m, m, m * 1.4, m * 1.5, m * 1.6, m * 200);
            samples.add(new Sample(t, 10_000, p, ts, 20, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
            t += 30_000; // ~17 hours in total
        }
        CapacityEstimate e = new ObservedCapacityEstimator(Thresholds.DEFAULT).estimate(samples);
        assertEquals(Confidence.MEDIUM, e.confidence());
        assertTrue(e.notes().stream().anyMatch(n -> n.key().equals("short-history")));
    }

    @Test
    void calibrationConvertsVirtualToRealPlayers() {
        List<StageResult> stages = new ArrayList<>();
        for (int v = 0; v <= 40; v += 4) {
            stages.add(stage(0, v, 10 + v));
        }
        BenchmarkCapacityEstimator est = new BenchmarkCapacityEstimator(Thresholds.DEFAULT);
        CapacityEstimate raw = est.estimate(stages, 0);
        CapacityEstimate cal = est.estimate(stages, 0, new Calibration(2.0, 0.05, "x", 0));
        assertEquals(raw.recommended().point() / 2, cal.recommended().point(), 1e-6);
        assertTrue(cal.recommended().low() < cal.recommended().point());
        assertTrue(cal.isCalibrated());
        assertEquals(2.0, cal.perPlayerMs() / raw.perPlayerMs(), 1e-9);
        assertTrue(cal.notes().stream().anyMatch(n -> n.key().equals("calibrated")));
    }

    @Test
    void calibrationIsOnlyDerivedFromTrustworthyData() {
        CapacityEstimate obs = estimateWith(CapacityEstimate.Source.OBSERVED, Confidence.HIGH, 1.5, 0.1);
        CapacityEstimate bench = estimateWith(CapacityEstimate.Source.BENCHMARK, Confidence.MEDIUM, 1.0, 0.1);
        Calibration c = Calibration.derive(obs, bench, "id", 0).orElseThrow();
        assertEquals(1.5, c.factor(), 1e-9);
        CapacityEstimate weak = estimateWith(CapacityEstimate.Source.OBSERVED, Confidence.LOW, 1.5, 0.1);
        assertTrue(Calibration.derive(weak, bench, "id", 0).isEmpty());
        CapacityEstimate absurd = estimateWith(CapacityEstimate.Source.OBSERVED, Confidence.HIGH, 50, 1);
        assertTrue(Calibration.derive(absurd, bench, "id", 0).isEmpty());
    }

    private static CapacityEstimate estimateWith(CapacityEstimate.Source src, Confidence c, double k, double se) {
        return new CapacityEstimate(src, c, new Range(20, 18, 22), new Range(40, 35, 45), k, se, 5, 0.9, 100, 6,
                20, 15, List.of(), false, 0, List.of(), List.of());
    }

    @Test
    void repeatedRunsNarrowTheInterval() {
        Random rnd = new Random(12);
        List<StageResult> one = new ArrayList<>();
        List<StageResult> three = new ArrayList<>();
        for (int run = 0; run < 3; run++) {
            for (int v = 0; v <= 12; v += 2) {
                StageResult s = stage(run, v, 5 + 1.0 * v + rnd.nextGaussian() * 1.5);
                three.add(s);
                if (run == 0) {
                    one.add(s);
                }
            }
        }
        BenchmarkCapacityEstimator est = new BenchmarkCapacityEstimator(Thresholds.DEFAULT);
        CapacityEstimate e1 = est.estimate(one, 0);
        CapacityEstimate e3 = est.estimate(three, 0);
        assertTrue(e3.recommended().relativeWidth() < e1.recommended().relativeWidth());
        assertTrue(e3.notes().stream().anyMatch(n -> n.key().equals("repeated-runs")));
        assertEquals(7, e3.distinctLoadLevels());
    }

    @Test
    void activityAnalysisSeparatesExplorationCost() {
        Random rnd = new Random(13);
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            int p = rnd.nextInt(15);
            int newChunks = p == 0 ? 0 : (rnd.nextInt(4) == 0 ? rnd.nextInt(30 * p) : rnd.nextInt(3 * p + 1));
            double rate = newChunks / 10.0;
            double m = 5 + 0.8 * p + 0.5 * rate + rnd.nextGaussian() * 0.5;
            TickSummary t = new TickSummary(200, m, m, m * 1.3, m * 1.4, m * 1.6, m * 200);
            samples.add(new Sample(0, 10_000, p, t, 20, 0.2, 0.2, m * 200, 1000, 4000, 1, 5, 1000, 500, 100,
                    newChunks));
        }
        ActivityAnalyzer.Result r = new ActivityAnalyzer(Thresholds.DEFAULT).analyze(samples);
        assertTrue(r.available());
        assertEquals(0.8, r.perPlayerMs(), 0.1);
        assertEquals(0.5, r.perChunkPerSecondMs(), 0.1);
        var quiet = r.scenarios().get(0);
        var typical = r.scenarios().get(1);
        var heavy = r.scenarios().get(2);
        assertEquals((30 - 5) / 0.8, quiet.recommended(), 3);
        assertTrue(quiet.recommended() > typical.recommended());
        assertTrue(typical.recommended() >= heavy.recommended());
    }

    @Test
    void activityAnalysisNeedsExplorationData() {
        List<Sample> samples = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            samples.add(TestData.sample(i % 5, 5 + i % 5, 8)); // newChunks = 0 everywhere
        }
        assertFalse(new ActivityAnalyzer(Thresholds.DEFAULT).analyze(samples).available());
    }
}

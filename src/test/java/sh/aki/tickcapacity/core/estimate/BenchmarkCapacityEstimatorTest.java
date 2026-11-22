package sh.aki.tickcapacity.core.estimate;

import org.junit.jupiter.api.Test;
import sh.aki.tickcapacity.core.model.TickSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToDoubleFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkCapacityEstimatorTest {

    private final BenchmarkCapacityEstimator estimator = new BenchmarkCapacityEstimator(Thresholds.DEFAULT);

    private static List<StageResult> stages(int max, int step, IntToDoubleFunction mspt) {
        List<StageResult> list = new ArrayList<>();
        for (int v = 0; v <= max; v += step) {
            double m = mspt.applyAsDouble(v);
            list.add(new StageResult(0, v, new TickSummary(600, m, m, m * 1.2, m * 1.3, m * 1.5, m * 600),
                    30_000, 0.3, 10, 1000, 1000, 500, 0));
        }
        return list;
    }

    @Test
    void measuredCrossingIsInterpolated() {
        CapacityEstimate e = estimator.estimate(stages(40, 4, v -> 10 + v), 0);
        // mean 30 at 20; p95 = 1.2*mean <= 50 at mean 41.7 -> 31.7; limit 50 at 40
        assertEquals(20, e.recommended().point(), 1e-6);
        assertEquals(40, e.limit().point(), 1e-6);
        assertEquals(Confidence.MEDIUM, e.confidence());
        assertTrue(e.notes().stream().anyMatch(n -> n.key().equals("synthetic-load")));
        assertEquals(20, e.provenComfortablePlayers()); // 30 ms is still within "comfortable"
    }

    @Test
    void realPlayersShiftTheResult() {
        CapacityEstimate e = estimator.estimate(stages(40, 4, v -> 10 + v), 5);
        assertEquals(25, e.recommended().point(), 1e-6);
        assertEquals(45, e.maxObservedPlayers());
    }

    @Test
    void notReachingThresholdExtrapolatesWithLowConfidence() {
        CapacityEstimate e = estimator.estimate(stages(10, 2, v -> 5 + 0.5 * v + (v % 4 == 0 ? 0.3 : -0.3)), 0);
        assertEquals(50, e.recommended().point(), 3);
        assertEquals(Confidence.LOW, e.confidence());
        assertTrue(e.notes().stream().anyMatch(n -> n.key().equals("extrapolated")));
    }

    @Test
    void tooFewStagesIsInsufficient() {
        CapacityEstimate e = estimator.estimate(stages(2, 2, v -> 10 + v), 0);
        assertEquals(Confidence.INSUFFICIENT, e.confidence());
    }

    @Test
    void oneOffSpikeStageDoesNotDecideTheCrossing() {
        // Reproduces a real run: the 4-player stage caught chunk loading (p95 69 ms), later stages were fine.
        List<StageResult> list = new ArrayList<>();
        double[][] data = {{0, 0.7, 2.1}, {2, 5.6, 9.1}, {4, 20.0, 69.1}, {7, 13.6, 18.2}, {14, 21.6, 37.4},
                {21, 31.0, 45.0}, {28, 41.0, 60.0}, {33, 50.5, 75.0}};
        for (double[] d : data) {
            list.add(new StageResult(0, (int) d[0], new TickSummary(400, d[1], d[1], d[2], d[2], d[2], d[1] * 400),
                    20_000, 0.3, 0, 0, 0, 0, 0));
        }
        CapacityEstimate e = estimator.estimate(list, 0);
        assertTrue(e.recommended().point() > 15, "recommended " + e.recommended());
        assertTrue(e.limit().point() > 28, "limit " + e.limit());
    }

    @Test
    void flatteningSyntheticLoadIsFlaggedAndLowConfidence() {
        // Measured on a real server: cost per virtual player collapses after ~8 (no random ticks / spawning).
        List<StageResult> list = new ArrayList<>();
        double[][] data = {{0, 0.3}, {2, 6.5}, {4, 8.3}, {8, 13.8}, {16, 13.5}, {31, 16.1}};
        for (double[] d : data) {
            list.add(new StageResult(0, (int) d[0], new TickSummary(400, d[1], d[1], d[1] * 1.3, d[1] * 1.4,
                    d[1] * 2, d[1] * 400), 20_000, 0.3, 0, 0, 0, 0, 0));
        }
        CapacityEstimate e = estimator.estimate(list, 0);
        assertEquals(Confidence.LOW, e.confidence());
        assertTrue(e.notes().stream().anyMatch(n -> n.key().equals("synthetic-flattening")));
        // A clean linear benchmark is not flagged.
        CapacityEstimate clean = estimator.estimate(stages(40, 4, v -> 10 + v), 0);
        assertTrue(clean.notes().stream().noneMatch(n -> n.key().equals("synthetic-flattening")));
    }
}

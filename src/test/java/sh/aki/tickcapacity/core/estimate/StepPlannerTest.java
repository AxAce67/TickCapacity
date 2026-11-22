package sh.aki.tickcapacity.core.estimate;

import org.junit.jupiter.api.Test;
import sh.aki.tickcapacity.core.model.TickSummary;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepPlannerTest {

    private static StageResult stage(int v, double mean) {
        return new StageResult(0, v, new TickSummary(400, mean, mean, mean, mean, mean, mean * 400), 20_000, 0, 0, 0,
                0, 0, 0);
    }

    /** Simulates a run on a server with the given base and per-player cost; returns the number of stages. */
    private static int stagesToLimit(double base, double perPlayer, int max) {
        List<StageResult> stages = new ArrayList<>();
        int v = 0;
        stages.add(stage(0, base));
        while (true) {
            int next = StepPlanner.next(stages, v, max, 0, 50);
            assertTrue(next > v);
            v = next;
            double mean = base + perPlayer * v;
            stages.add(stage(v, mean));
            if (mean >= 50 || v >= max) {
                return stages.size();
            }
        }
    }

    @Test
    void adaptiveNeedsFarFewerStagesThanFixedSteps() {
        // 1 ms per player from 5 ms: limit at 45 players. Fixed steps of 2 would need 24 stages.
        int stages = stagesToLimit(5, 1.0, 200);
        assertTrue(stages <= 12, "stages " + stages);
    }

    @Test
    void stopsAtMaxOnStrongServers() {
        int stages = stagesToLimit(2, 0.05, 40);
        assertTrue(stages <= 8, "stages " + stages);
    }

    @Test
    void neverMoreThanDoubles() {
        List<StageResult> s = List.of(stage(0, 1), stage(2, 1.01)); // almost no cost measured yet
        assertEquals(4, StepPlanner.next(s, 2, 40, 0, 50)); // first step for max 40 is 2; never more than doubles
    }

    @Test
    void fixedStepIsRespected() {
        assertEquals(7, StepPlanner.next(List.of(stage(0, 1)), 5, 40, 2, 50));
        assertEquals(40, StepPlanner.next(List.of(stage(0, 1)), 39, 40, 2, 50));
    }
}

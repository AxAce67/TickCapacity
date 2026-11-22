package sh.aki.tickcapacity.core.analysis;

import org.junit.jupiter.api.Test;
import sh.aki.tickcapacity.core.analysis.AlertEvaluator.Type;
import sh.aki.tickcapacity.core.model.Sample;
import sh.aki.tickcapacity.core.model.TickSummary;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertAndHourlyTest {

    @Test
    void msptAlertNeedsSustainAndRecovers() {
        AlertEvaluator a = new AlertEvaluator(45, 30_000, 600_000);
        assertTrue(a.update(0, 50, 5, Double.NaN, Double.NaN).isEmpty());
        assertTrue(a.update(29_000, 50, 5, Double.NaN, Double.NaN).isEmpty());
        assertEquals(Type.MSPT_HIGH, a.update(30_000, 50, 5, Double.NaN, Double.NaN).getFirst().type());
        assertTrue(a.update(31_000, 50, 5, Double.NaN, Double.NaN).isEmpty()); // no repeat
        assertTrue(a.update(40_000, 42, 5, Double.NaN, Double.NaN).isEmpty()); // below thr but above 90%
        assertTrue(a.update(41_000, 20, 5, Double.NaN, Double.NaN).isEmpty());
        assertEquals(Type.MSPT_RECOVERED, a.update(71_000, 20, 5, Double.NaN, Double.NaN).getFirst().type());
    }

    @Test
    void briefSpikeDoesNotAlert() {
        AlertEvaluator a = new AlertEvaluator(45, 30_000, 600_000);
        a.update(0, 80, 5, Double.NaN, Double.NaN);
        a.update(10_000, 10, 5, Double.NaN, Double.NaN);
        assertTrue(a.update(35_000, 80, 5, Double.NaN, Double.NaN).isEmpty());
    }

    @Test
    void capacityAlertsRearmAndRespectCooldown() {
        AlertEvaluator a = new AlertEvaluator(45, 30_000, 600_000);
        assertEquals(Type.OVER_RECOMMENDED, a.update(0, 10, 21, 20.7, 40).getFirst().type());
        assertTrue(a.update(1000, 10, 22, 20.7, 40).isEmpty());
        a.update(2000, 10, 18, 20.7, 40); // drops back: re-arms
        assertTrue(a.update(3000, 10, 21, 20.7, 40).isEmpty(), "cooldown");
        assertEquals(Type.OVER_RECOMMENDED, a.update(700_000, 10, 21, 20.7, 40).getFirst().type());
        assertEquals(Type.OVER_LIMIT, a.update(701_000, 10, 40, 20.7, 40).getFirst().type());
    }

    @Test
    void hourlyProfileFindsPeak() {
        List<Sample> samples = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            int players = h == 21 ? 15 : 2;
            long t = LocalDateTime.of(2026, 11, 20, h, 30).toInstant(ZoneOffset.UTC).toEpochMilli();
            TickSummary ts = new TickSummary(200, 5 + players, 5, 8, 9, 10, 1000);
            samples.add(new Sample(t, 10_000, players, ts, 20, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        }
        HourlyProfile p = HourlyProfile.of(samples, ZoneOffset.UTC);
        assertEquals(21, p.peakPlayers().orElseThrow().hour());
        assertEquals(21, p.worstMspt().orElseThrow().hour());
        assertEquals(24, p.samples());
    }
}

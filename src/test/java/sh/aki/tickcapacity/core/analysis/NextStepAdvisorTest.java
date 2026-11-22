package sh.aki.tickcapacity.core.analysis;

import org.junit.jupiter.api.Test;
import sh.aki.tickcapacity.core.estimate.CapacityEstimate;
import sh.aki.tickcapacity.core.estimate.CapacityEstimate.Note;
import sh.aki.tickcapacity.core.estimate.Confidence;
import sh.aki.tickcapacity.core.estimate.Range;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NextStepAdvisorTest {

    private static CapacityEstimate observed(Confidence c, Note... notes) {
        return new CapacityEstimate(CapacityEstimate.Source.OBSERVED, c, new Range(20, 18, 22),
                new Range(35, 32, 38), 1, 0.1, 5, 0.9, 500, 8, 12, 12, List.of(notes), false, 0, List.of(), List.of());
    }

    private static String advise(CapacityEstimate obs, boolean bench, boolean running, boolean ear, int online,
                                 Finding.Severity worst) {
        return NextStepAdvisor.advise(new NextStepAdvisor.Input(obs, bench, running, ear, online, 20, 35, worst,
                true)).key();
    }

    @Test
    void emptyServerWithoutDataSuggestsBenchmark() {
        CapacityEstimate none = CapacityEstimate.insufficient(CapacityEstimate.Source.OBSERVED, 0, 0, 0, -1,
                List.of(), List.of());
        assertEquals("next.run-benchmark", advise(none, false, false, false, 0, null));
        assertEquals("next.collect-data", advise(none, false, false, false, 5, null));
        assertEquals("next.collect-data-bench", advise(none, true, false, false, 0, null));
    }

    @Test
    void urgentThingsComeFirst() {
        CapacityEstimate ok = observed(Confidence.HIGH);
        assertEquals("next.bench-running", advise(ok, true, true, false, 0, Finding.Severity.CRITICAL));
        assertEquals("next.fix-critical", advise(ok, true, false, false, 0, Finding.Severity.CRITICAL));
        assertEquals("next.over-limit", advise(ok, true, false, false, 36, null));
        assertEquals("next.over-recommended", advise(ok, true, false, false, 21, null));
    }

    @Test
    void dataQualityHints() {
        assertEquals("next.more-days", advise(observed(Confidence.MEDIUM, new Note("short-history", 1.2)),
                false, false, false, 3, null));
        assertEquals("next.more-peak-data", advise(observed(Confidence.MEDIUM, new Note("extrapolated", 1.6)),
                false, false, false, 3, null));
        assertEquals("next.configure-ear", advise(observed(Confidence.LOW), true, false, true, 3, null));
        assertEquals("next.check-warning", advise(observed(Confidence.HIGH), false, false, false, 3,
                Finding.Severity.WARNING));
        assertEquals("next.all-good", advise(observed(Confidence.HIGH), false, false, false, 3, null));
    }

    @Test
    void moreDaysCountsDown() {
        var step = NextStepAdvisor.advise(new NextStepAdvisor.Input(
                observed(Confidence.MEDIUM, new Note("short-history", 1.2)), false, false, false, 3, 20, 35, null, true));
        assertEquals(List.of("2"), step.args());
    }
}

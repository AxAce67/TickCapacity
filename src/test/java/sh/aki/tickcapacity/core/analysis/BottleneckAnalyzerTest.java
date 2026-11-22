package sh.aki.tickcapacity.core.analysis;

import org.junit.jupiter.api.Test;
import sh.aki.tickcapacity.core.analysis.Finding.Category;
import sh.aki.tickcapacity.core.analysis.Finding.Severity;
import sh.aki.tickcapacity.core.model.Sample;
import sh.aki.tickcapacity.core.model.TickSummary;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BottleneckAnalyzerTest {

    private final BottleneckAnalyzer analyzer = new BottleneckAnalyzer();
    private final BottleneckAnalyzer.Context ctx = new BottleneckAnalyzer.Context(8, 8192, 10, 10, 30, 50);

    private static Sample sample(double mspt, double mainCpuShare, long gcMs, long heapUsed, int entities) {
        TickSummary t = new TickSummary(200, mspt, mspt, mspt * 1.3, mspt * 1.5, mspt * 2, mspt * 200);
        return new Sample(0, 10_000, 10, t, 20, 0.3, 0.4, mspt * 200 * mainCpuShare, heapUsed, 8192, 3, gcMs,
                2000, entities, 300, 0);
    }

    @Test
    void healthyServer() {
        List<Finding> f = analyzer.analyze(List.of(sample(10, 1, 20, 2000, 800)), ctx);
        assertEquals(Category.HEALTHY, f.getFirst().category());
    }

    @Test
    void gcPressureIsCriticalAndFirst() {
        List<Finding> f = analyzer.analyze(List.of(sample(40, 1, 1500, 7800, 800)), ctx);
        assertEquals(Severity.CRITICAL, f.getFirst().severity());
        assertTrue(f.stream().anyMatch(x -> x.category() == Category.GC && x.severity() == Severity.CRITICAL));
        assertTrue(f.stream().anyMatch(x -> x.category() == Category.MEMORY));
    }

    @Test
    void waitingMainThreadIsDetected() {
        List<Finding> f = analyzer.analyze(List.of(sample(45, 0.4, 10, 2000, 800)), ctx);
        assertTrue(f.stream().anyMatch(x -> x.key().equals("main-thread-waiting")));
        assertTrue(f.stream().anyMatch(x -> x.key().equals("mspt-high")));
    }

    @Test
    void entityHeavyWorld() {
        List<Finding> f = analyzer.analyze(List.of(sample(35, 1, 10, 2000, 12_000)), ctx);
        assertTrue(f.stream().anyMatch(x -> x.category() == Category.ENTITIES && x.severity() == Severity.WARNING));
    }

    @Test
    void weakHardwareIsFlaggedEvenWithoutSamples() {
        List<Finding> f = analyzer.analyze(List.of(), new BottleneckAnalyzer.Context(1, 1024, 10, 10, 30, 50));
        assertTrue(f.stream().anyMatch(x -> x.key().equals("few-cores")));
        assertTrue(f.stream().anyMatch(x -> x.key().equals("small-heap")));
    }
}

package sh.aki.tickcapacity.core.estimate;

import sh.aki.tickcapacity.core.model.Sample;
import sh.aki.tickcapacity.core.model.TickSummary;

final class TestData {

    private TestData() {
    }

    /** Increasing timestamps, 17 minutes apart, so test data spans several days and many hour blocks. */
    private static long clock = 1_700_000_000_000L;

    static Sample sample(int players, double meanMs, double p95Ms) {
        TickSummary t = new TickSummary(200, meanMs, meanMs, p95Ms, p95Ms * 1.1, p95Ms * 1.3, meanMs * 200);
        clock += 17 * 60_000L;
        return new Sample(clock, 10_000, players, t, 20, 0.2, 0.3, meanMs * 200, 1000, 4000, 1, 5,
                1000 + players * 100, 500 + players * 50, 200, 0);
    }
}

package sh.aki.tickcapacity.core.analysis;

import sh.aki.tickcapacity.core.estimate.CapacityEstimate;
import sh.aki.tickcapacity.core.estimate.Confidence;

import java.util.List;
import java.util.Locale;

/** The one thing the operator should do next. Rules are checked in order; the first match wins. */
public final class NextStepAdvisor {

    public record Input(CapacityEstimate observed, boolean hasBenchmark, boolean benchmarkRunning, boolean earActive,
                        int online, double recommended, double limit, Finding.Severity worst,
                        boolean canRunBenchmark) {
    }

    /** {@code command} is what the button runs, or null for no button. */
    public record Step(String key, List<String> args, String command) {
        static Step of(String key, String command, String... args) {
            return new Step(key, List.of(args), command);
        }
    }

    private NextStepAdvisor() {
    }

    public static Step advise(Input in) {
        CapacityEstimate obs = in.observed();
        if (in.benchmarkRunning()) {
            return Step.of("next.bench-running", "/tc bench");
        }
        if (in.worst() == Finding.Severity.CRITICAL) {
            return Step.of("next.fix-critical", "/tc perf");
        }
        if (Double.isFinite(in.limit()) && in.online() >= Math.max(1, Math.floor(in.limit()))) {
            return Step.of("next.over-limit", "/tc perf", fmt(Math.floor(in.limit())));
        }
        if (Double.isFinite(in.recommended()) && in.online() > Math.floor(in.recommended())) {
            return Step.of("next.over-recommended", "/tc perf", fmt(Math.floor(in.recommended())));
        }
        if (obs.confidence() == Confidence.INSUFFICIENT) {
            if (!in.hasBenchmark() && in.canRunBenchmark() && in.online() <= 1) {
                return Step.of("next.run-benchmark", "/tc bench");
            }
            return in.hasBenchmark() ? Step.of("next.collect-data-bench", "/tc capacity")
                    : Step.of("next.collect-data", "/tc capacity");
        }
        double days = noteValue(obs, "short-history");
        if (!Double.isNaN(days)) {
            return Step.of("next.more-days", "/tc capacity", fmt(Math.max(1, Math.ceil(3 - days))));
        }
        if (!Double.isNaN(noteValue(obs, "extrapolated"))) {
            return Step.of("next.more-peak-data", "/tc capacity", fmt(obs.maxObservedPlayers()));
        }
        if (in.hasBenchmark() && in.earActive() && obs.confidence().compareTo(Confidence.MEDIUM) < 0) {
            return Step.of("next.configure-ear", "/tc bench");
        }
        if (obs.confidence() == Confidence.LOW) {
            return Step.of("next.collect-data", "/tc capacity");
        }
        if (in.worst() == Finding.Severity.WARNING) {
            return Step.of("next.check-warning", "/tc perf");
        }
        return Step.of("next.all-good", null);
    }

    private static double noteValue(CapacityEstimate e, String key) {
        return e.notesOrEmpty().stream().filter(n -> n.key().equals(key)).findFirst()
                .map(n -> Double.isNaN(n.value()) ? 0 : n.value()).orElse(Double.NaN);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }
}

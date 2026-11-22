package sh.aki.tickcapacity.core.estimate;

import sh.aki.tickcapacity.core.model.Sample;
import sh.aki.tickcapacity.core.stats.RobustRegression;
import sh.aki.tickcapacity.core.stats.Stats;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits MSPT into a per-player part and a part caused by generating new chunks (robust regression over
 * individual samples) and derives capacities for quiet, typical and exploration-heavy days. There are no
 * intervals: with autocorrelated samples they would be far too narrow.
 */
public final class ActivityAnalyzer {

    public static final int MIN_SAMPLES = 60;

    /** Rates are new chunks per second per player; "heavy" is the 90th percentile. */
    public record Result(boolean available, String reasonKey, double baseMs, double perPlayerMs,
                         double perChunkPerSecondMs, double perChunkPerSecondStdError, double typicalRatePerPlayer,
                         double heavyRatePerPlayer, List<Scenario> scenarios, int samples) {

        static Result unavailable(String reasonKey, int samples) {
            return new Result(false, reasonKey, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, List.of(), samples);
        }
    }

    public record Scenario(String key, double chunksPerSecondPerPlayer, double recommended, double limit) {
    }

    private final Thresholds thresholds;

    public ActivityAnalyzer(Thresholds thresholds) {
        this.thresholds = thresholds;
    }

    public Result analyze(List<Sample> all) {
        List<Sample> samples = all.stream()
                .filter(s -> s.ticks().ticks() >= 20 && s.newChunks() >= 0 && !Double.isNaN(s.ticks().meanMs()))
                .toList();
        if (samples.size() < MIN_SAMPLES) {
            return Result.unavailable("activity.too-few-samples", samples.size());
        }
        long distinctPlayers = samples.stream().mapToInt(Sample::players).distinct().count();
        long exploring = samples.stream().filter(s -> s.players() > 0 && s.newChunks() > 0).count();
        if (distinctPlayers < 3 || exploring < samples.size() * 0.05) {
            return Result.unavailable("activity.too-little-variation", samples.size());
        }

        double[][] x = new double[samples.size()][];
        double[] y = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) {
            Sample s = samples.get(i);
            x[i] = new double[]{s.players(), s.newChunksPerSecond()};
            y[i] = s.ticks().meanMs();
        }
        RobustRegression fit;
        try {
            fit = RobustRegression.fit(x, y);
        } catch (IllegalArgumentException e) {
            return Result.unavailable("activity.too-little-variation", samples.size());
        }
        double base = fit.coefficient(0);
        double perPlayer = fit.coefficient(1);
        double perRate = fit.coefficient(2);

        double sumRate = 0;
        double sumPlayers = 0;
        List<Double> ratios = new ArrayList<>();
        for (Sample s : samples) {
            if (s.players() > 0) {
                sumRate += s.newChunksPerSecond();
                sumPlayers += s.players();
                ratios.add(s.newChunksPerSecond() / s.players());
            }
        }
        double typical = sumPlayers > 0 ? sumRate / sumPlayers : 0;
        double heavy = Stats.percentile(ratios.stream().mapToDouble(Double::doubleValue).toArray(), ratios.size(), 0.9);
        heavy = Math.max(heavy, typical);

        List<Scenario> scenarios = List.of(
                scenario("quiet", 0, base, perPlayer, perRate),
                scenario("typical", typical, base, perPlayer, perRate),
                scenario("heavy", heavy, base, perPlayer, perRate));
        return new Result(true, null, base, perPlayer, perRate, fit.standardError(2), typical, heavy, scenarios,
                samples.size());
    }

    private Scenario scenario(String key, double rate, double base, double perPlayer, double perRate) {
        double cost = perPlayer + perRate * rate;
        return new Scenario(key, rate, crossing(base, cost, thresholds.comfortableMspt()),
                crossing(base, cost, thresholds.limitMspt()));
    }

    private static double crossing(double base, double costPerPlayer, double threshold) {
        if (costPerPlayer <= 0) {
            return base >= threshold ? 0 : Double.POSITIVE_INFINITY;
        }
        double n = Math.max(0, (threshold - base) / costPerPlayer);
        return n > CapacitySolver.MAX_PLAYERS ? Double.POSITIVE_INFINITY : n;
    }
}

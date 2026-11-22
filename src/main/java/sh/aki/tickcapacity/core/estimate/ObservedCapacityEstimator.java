package sh.aki.tickcapacity.core.estimate;

import sh.aki.tickcapacity.core.estimate.CapacityEstimate.LoadPoint;
import sh.aki.tickcapacity.core.estimate.CapacityEstimate.Note;
import sh.aki.tickcapacity.core.model.Sample;
import sh.aki.tickcapacity.core.stats.Distributions;
import sh.aki.tickcapacity.core.stats.FittedModel;
import sh.aki.tickcapacity.core.stats.LinearFit;
import sh.aki.tickcapacity.core.stats.ModelSelection;
import sh.aki.tickcapacity.core.stats.QuadraticFit;
import sh.aki.tickcapacity.core.stats.Stats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Capacity from real gameplay. Samples are grouped by player count, the median MSPT of each group is
 * fitted against the count, and the interval is the wider of the regression band and a bootstrap over
 * one-hour blocks (samples within the same hour are not independent).
 */
public final class ObservedCapacityEstimator {

    /** Shorter windows usually mean the server was paused. */
    static final int MIN_TICKS_PER_SAMPLE = 20;
    /** With less history, day-to-day variation is unknown, so confidence is capped. */
    static final double MIN_DAYS_FOR_HIGH = 3;
    static final long BLOCK_MS = 3_600_000L;
    static final int MIN_BLOCKS_FOR_BOOTSTRAP = 6;

    private final Thresholds thresholds;

    public ObservedCapacityEstimator(Thresholds thresholds) {
        this.thresholds = thresholds;
    }

    private record Fit(ModelSelection.Choice mean, Range recommended, Range limit, double t) {
    }

    private record Bootstrap(Range recommended, Range limit, double slopeStdDev) {
    }

    public CapacityEstimate estimate(List<Sample> samples) {
        List<Sample> usable = samples.stream()
                .filter(s -> s.ticks().ticks() >= MIN_TICKS_PER_SAMPLE && !Double.isNaN(s.ticks().meanMs()))
                .toList();
        List<LoadPoint> groups = group(usable);
        int used = groups.stream().mapToInt(LoadPoint::samples).sum();

        int maxObserved = groups.stream().mapToInt(g -> (int) g.players()).max().orElse(0);
        int proven = groups.stream()
                .filter(g -> g.meanMs() <= thresholds.comfortableMspt()
                        && (Double.isNaN(g.p95Ms()) || g.p95Ms() <= thresholds.comfortableP95Mspt()))
                .mapToInt(g -> (int) g.players()).max().orElse(-1);

        List<Note> notes = new ArrayList<>();
        if (used < thresholds.minSamples()) {
            notes.add(new Note("too-few-samples", thresholds.minSamples() - used));
        }
        if (groups.size() < 3) {
            notes.add(new Note("too-few-player-counts", groups.size()));
        }
        if (!notes.isEmpty()) {
            return CapacityEstimate.insufficient(CapacityEstimate.Source.OBSERVED, used, groups.size(),
                    maxObserved, proven, notes, groups);
        }

        Fit fit = fit(groups);
        LinearFit linear = fit.mean().linear();
        Range recommended = fit.recommended();
        Range limit = fit.limit();
        double slopeSe = linear.slopeStandardError();

        Bootstrap boot = bootstrap(usable, fit.mean().curved());
        if (boot != null) {
            recommended = widen(recommended, boot.recommended());
            limit = widen(limit, boot.limit());
            slopeSe = Math.max(slopeSe, boot.slopeStdDev());
        }

        double days = historyDays(usable);
        if (days < MIN_DAYS_FOR_HIGH) {
            notes.add(new Note("short-history", days));
        }
        if (fit.mean().curved()) {
            notes.add(Note.of("curved-model"));
        }
        if (linear.slopeTStatistic() < 2) {
            notes.add(Note.of("no-clear-load-trend"));
        }
        if (fit.mean().model().rSquared() < 0.3) {
            notes.add(new Note("weak-fit", fit.mean().model().rSquared()));
        }
        double extrapolation = maxObserved > 0 ? recommended.point() / maxObserved : Double.POSITIVE_INFINITY;
        if (extrapolation > 1.25) {
            notes.add(new Note("extrapolated", extrapolation));
        }

        Confidence confidence = grade(linear, recommended, extrapolation, groups.size());
        if (days < MIN_DAYS_FOR_HIGH && confidence == Confidence.HIGH) {
            confidence = Confidence.MEDIUM;
        }
        var curve = CapacitySolver.curve(fit.mean().model(), fit.t(), CapacitySolver.chartMax(maxObserved, limit),
                v -> v);
        return new CapacityEstimate(CapacityEstimate.Source.OBSERVED, confidence, recommended, limit,
                linear.slope(), slopeSe, linear.intercept(), fit.mean().model().rSquared(),
                used, groups.size(), maxObserved, proven, List.copyOf(notes), fit.mean().curved(), 0,
                List.copyOf(groups), curve);
    }

    private List<LoadPoint> group(List<Sample> samples) {
        Map<Integer, List<Sample>> byPlayers = new TreeMap<>();
        for (Sample s : samples) {
            byPlayers.computeIfAbsent(s.players(), k -> new ArrayList<>()).add(s);
        }
        List<LoadPoint> groups = new ArrayList<>();
        for (Map.Entry<Integer, List<Sample>> e : byPlayers.entrySet()) {
            List<Sample> g = e.getValue();
            if (g.size() < thresholds.minSamplesPerGroup()) {
                continue;
            }
            double[] means = g.stream().mapToDouble(s -> s.ticks().meanMs()).toArray();
            double[] p95s = g.stream().mapToDouble(s -> s.ticks().p95Ms()).filter(v -> !Double.isNaN(v)).toArray();
            groups.add(new LoadPoint(e.getKey(), Stats.median(means),
                    p95s.length > 0 ? Stats.median(p95s) : Double.NaN, g.size()));
        }
        return groups;
    }

    private Fit fit(List<LoadPoint> groups) {
        int n = groups.size();
        double[] x = new double[n];
        double[] yMean = new double[n];
        double[] w = new double[n];
        List<double[]> p95Points = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            LoadPoint g = groups.get(i);
            x[i] = g.players();
            yMean[i] = g.meanMs();
            w[i] = Math.min(g.samples(), thresholds.groupWeightCap());
            if (!Double.isNaN(g.p95Ms())) {
                p95Points.add(new double[]{g.players(), g.p95Ms(), w[i]});
            }
        }
        ModelSelection.Choice mean = ModelSelection.select(x, yMean, w);
        double t = Distributions.tCritical(thresholds.confidence(), mean.model().degreesOfFreedom());
        Range limit = CapacitySolver.solve(mean.model(), thresholds.limitMspt(), t);
        Range recommended = CapacitySolver.solve(mean.model(), thresholds.comfortableMspt(), t);
        if (p95Points.size() >= 3) {
            double[] px = p95Points.stream().mapToDouble(p -> p[0]).toArray();
            double[] py = p95Points.stream().mapToDouble(p -> p[1]).toArray();
            double[] pw = p95Points.stream().mapToDouble(p -> p[2]).toArray();
            ModelSelection.Choice p95 = ModelSelection.select(px, py, pw);
            double tp = Distributions.tCritical(thresholds.confidence(), p95.model().degreesOfFreedom());
            recommended = Range.min(recommended, CapacitySolver.solve(p95.model(), thresholds.comfortableP95Mspt(), tp));
        }
        return new Fit(mean, recommended, limit, t);
    }

    /** Returns null when there are too few hour blocks to resample. */
    private Bootstrap bootstrap(List<Sample> samples, boolean curved) {
        Map<Long, List<Sample>> byBlock = new LinkedHashMap<>();
        for (Sample s : samples) {
            byBlock.computeIfAbsent(Math.floorDiv(s.epochMillis(), BLOCK_MS), k -> new ArrayList<>()).add(s);
        }
        List<List<Sample>> blocks = new ArrayList<>(byBlock.values());
        if (blocks.size() < MIN_BLOCKS_FOR_BOOTSTRAP) {
            return null;
        }
        int reps = Math.clamp(20_000_000L / Math.max(1, samples.size()), 50, 200);
        Random rnd = new Random(42); // deterministic: the same data always gives the same answer
        List<Double> rec = new ArrayList<>();
        List<Double> lim = new ArrayList<>();
        List<Double> slopes = new ArrayList<>();
        for (int r = 0; r < reps; r++) {
            List<Sample> resampled = new ArrayList<>(samples.size());
            for (int b = 0; b < blocks.size(); b++) {
                resampled.addAll(blocks.get(rnd.nextInt(blocks.size())));
            }
            List<LoadPoint> groups = group(resampled);
            if (groups.size() < 3) {
                continue;
            }
            double[] point = pointEstimates(groups, curved);
            if (point != null) {
                rec.add(point[0]);
                lim.add(point[1]);
                slopes.add(point[2]);
            }
        }
        if (rec.size() < reps / 2) {
            return null;
        }
        double alpha = (1 - thresholds.confidence()) / 2;
        double[] finiteSlopes = slopes.stream().mapToDouble(Double::doubleValue).filter(Double::isFinite).toArray();
        return new Bootstrap(percentileRange(rec, alpha), percentileRange(lim, alpha), stdDev(finiteSlopes));
    }

    /** {recommended, limit, slope}, or null if the fit fails. */
    private double[] pointEstimates(List<LoadPoint> groups, boolean curved) {
        int n = groups.size();
        double[] x = new double[n];
        double[] y = new double[n];
        double[] p = new double[n];
        double[] w = new double[n];
        for (int i = 0; i < n; i++) {
            LoadPoint g = groups.get(i);
            x[i] = g.players();
            y[i] = g.meanMs();
            p[i] = Double.isNaN(g.p95Ms()) ? g.meanMs() : g.p95Ms();
            w[i] = Math.min(g.samples(), thresholds.groupWeightCap());
        }
        try {
            LinearFit line = LinearFit.fit(x, y, w);
            FittedModel mean = curved && n >= ModelSelection.MIN_POINTS_FOR_CURVE ? QuadraticFit.fit(x, y, w) : line;
            FittedModel p95 = curved && n >= ModelSelection.MIN_POINTS_FOR_CURVE ? QuadraticFit.fit(x, p, w)
                    : LinearFit.fit(x, p, w);
            double rec = Math.min(CapacitySolver.point(mean, thresholds.comfortableMspt()),
                    CapacitySolver.point(p95, thresholds.comfortableP95Mspt()));
            return new double[]{rec, CapacitySolver.point(mean, thresholds.limitMspt()), line.slope()};
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Nearest rank, so replicates without a limit (infinity) do not produce NaN. */
    static Range percentileRange(List<Double> values, double alpha) {
        double[] v = values.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(v);
        int lo = (int) Math.floor(alpha * (v.length - 1));
        int hi = (int) Math.ceil((1 - alpha) * (v.length - 1));
        return new Range(v[v.length / 2], v[lo], v[hi]);
    }

    private static Range widen(Range a, Range b) {
        return new Range(a.point(), Math.min(a.low(), b.low()), Math.max(a.high(), b.high()));
    }

    private static double stdDev(double[] v) {
        if (v.length < 2) {
            return 0;
        }
        double mean = Stats.mean(v);
        double ss = 0;
        for (double d : v) {
            ss += (d - mean) * (d - mean);
        }
        return Math.sqrt(ss / (v.length - 1));
    }

    static double historyDays(List<Sample> samples) {
        long min = Long.MAX_VALUE;
        long max = Long.MIN_VALUE;
        for (Sample s : samples) {
            min = Math.min(min, s.epochMillis());
            max = Math.max(max, s.epochMillis());
        }
        return samples.isEmpty() ? 0 : (max - min) / 86_400_000.0;
    }

    static Confidence grade(LinearFit fit, Range recommended, double extrapolation, int levels) {
        if (fit.slopeTStatistic() < 2 || !Double.isFinite(recommended.point())) {
            return Confidence.LOW;
        }
        double width = recommended.relativeWidth();
        if (extrapolation <= 1.25 && levels >= 5 && width <= 0.5) {
            return Confidence.HIGH;
        }
        if (extrapolation <= 2.0 && levels >= 4 && width <= 1.0) {
            return Confidence.MEDIUM;
        }
        return Confidence.LOW;
    }
}

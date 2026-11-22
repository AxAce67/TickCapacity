package sh.aki.tickcapacity.core.estimate;

import sh.aki.tickcapacity.core.estimate.CapacityEstimate.LoadPoint;
import sh.aki.tickcapacity.core.estimate.CapacityEstimate.Note;
import sh.aki.tickcapacity.core.stats.Distributions;
import sh.aki.tickcapacity.core.stats.FittedModel;
import sh.aki.tickcapacity.core.stats.ModelSelection;
import sh.aki.tickcapacity.core.stats.Stats;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Capacity from benchmark stages. A threshold crossed during the run is interpolated between stages,
 * otherwise the fitted model is extrapolated. Never graded above MEDIUM: virtual players only approximate
 * real ones.
 */
public final class BenchmarkCapacityEstimator {

    private final Thresholds thresholds;

    public BenchmarkCapacityEstimator(Thresholds thresholds) {
        this.thresholds = thresholds;
    }

    public CapacityEstimate estimate(List<StageResult> stages, int realPlayers) {
        return estimate(stages, realPlayers, null);
    }

    /** @param realPlayers players online during the benchmark; their load is part of the baseline */
    public CapacityEstimate estimate(List<StageResult> stages, int realPlayers, Calibration calibration) {
        List<StageResult> usable = stages.stream().filter(s -> !s.ticks().isEmpty()).toList();
        int maxVirtual = usable.stream().mapToInt(StageResult::virtualPlayers).max().orElse(0);
        long levels = usable.stream().mapToInt(StageResult::virtualPlayers).distinct().count();
        double f = calibration == null ? 1 : calibration.factor();
        List<Note> notes = new ArrayList<>();
        notes.add(Note.of("synthetic-load"));

        // One point per level (median over runs), in real-player units.
        Map<Integer, List<StageResult>> byLevel = new TreeMap<>();
        usable.forEach(s -> byLevel.computeIfAbsent(s.virtualPlayers(), k -> new ArrayList<>()).add(s));
        List<LoadPoint> points = new ArrayList<>();
        byLevel.forEach((v, list) -> points.add(new LoadPoint(realPlayers + v / f,
                Stats.median(list.stream().mapToDouble(s -> s.ticks().meanMs()).toArray()),
                Stats.median(list.stream().mapToDouble(s -> s.ticks().p95Ms()).toArray()), list.size())));

        int proven = -1;
        for (LoadPoint p : points) {
            if (p.meanMs() <= thresholds.comfortableMspt() && p.p95Ms() <= thresholds.comfortableP95Mspt()) {
                proven = (int) Math.floor(p.players());
            } else {
                break;
            }
        }

        if (levels < 3) {
            notes.add(new Note("too-few-stages", levels));
            return CapacityEstimate.insufficient(CapacityEstimate.Source.BENCHMARK, usable.size(), (int) levels,
                    realPlayers + (int) Math.floor(maxVirtual / f), proven, notes, points);
        }

        int n = usable.size();
        double[] x = new double[n];
        double[] mean = new double[n];
        double[] p95 = new double[n];
        double[] w = new double[n];
        Arrays.fill(w, 1);
        for (int i = 0; i < n; i++) {
            StageResult s = usable.get(i);
            x[i] = s.virtualPlayers();
            mean[i] = s.ticks().meanMs();
            p95[i] = s.ticks().p95Ms();
        }
        ModelSelection.Choice meanChoice = ModelSelection.select(x, mean, w);
        ModelSelection.Choice p95Choice = ModelSelection.select(x, p95, w);
        double t = Distributions.tCritical(thresholds.confidence(), meanChoice.model().degreesOfFreedom());
        double tp = Distributions.tCritical(thresholds.confidence(), p95Choice.model().degreesOfFreedom());

        Crossing limit = cross(meanChoice.model(), usable, false, thresholds.limitMspt(), t);
        Crossing recMean = cross(meanChoice.model(), usable, false, thresholds.comfortableMspt(), t);
        Crossing recP95 = cross(p95Choice.model(), usable, true, thresholds.comfortableP95Mspt(), tp);
        Range recommendedVirtual = Range.min(recMean.range, recP95.range);
        boolean recommendedMeasured = recMean.range.point() <= recP95.range.point() ? recMean.measured : recP95.measured;
        Range limitVirtual = limit.range;

        double extrapolation = maxVirtual > 0 ? recommendedVirtual.point() / maxVirtual : Double.POSITIVE_INFINITY;
        if (!recommendedMeasured && extrapolation > 1.0) {
            notes.add(new Note("extrapolated", extrapolation));
        }
        if (meanChoice.curved()) {
            notes.add(Note.of("curved-model"));
        }
        if (meanChoice.model().rSquared() < 0.5) {
            notes.add(new Note("weak-fit", meanChoice.model().rSquared()));
        }
        long runs = usable.stream().mapToInt(StageResult::run).distinct().count();
        if (runs > 1) {
            notes.add(new Note("repeated-runs", runs));
        }

        // Without a real player nearby the game skips random ticks and mob spawning, so after a while more
        // virtual players add almost nothing. Extrapolating from such a curve would be meaningless.
        boolean flattening = flattening(byLevel);
        if (flattening) {
            notes.add(Note.of("synthetic-flattening"));
        }

        Confidence confidence;
        if (flattening || meanChoice.linear().slope() <= 0 || !Double.isFinite(recommendedVirtual.point())) {
            confidence = Confidence.LOW;
        } else if ((recommendedMeasured && recommendedVirtual.relativeWidth() <= 2.0)
                || (extrapolation <= 1.5 && recommendedVirtual.relativeWidth() <= 1.0)) {
            confidence = Confidence.MEDIUM;
        } else {
            confidence = Confidence.LOW;
        }

        Range recommended = recommendedVirtual;
        Range limitRange = limitVirtual;
        double perPlayer = meanChoice.linear().slope();
        double perPlayerSe = meanChoice.linear().slopeStandardError();
        if (calibration != null) {
            recommended = calibration.apply(recommendedVirtual);
            limitRange = calibration.apply(limitVirtual);
            perPlayer *= f;
            perPlayerSe *= f;
            notes.add(new Note("calibrated", f));
        }

        double chartMaxVirtual = CapacitySolver.chartMax(maxVirtual, limitVirtual);
        var curve = CapacitySolver.curve(meanChoice.model(), t, chartMaxVirtual, v -> realPlayers + v / f);
        return new CapacityEstimate(CapacityEstimate.Source.BENCHMARK, confidence,
                recommended.shift(realPlayers), limitRange.shift(realPlayers),
                perPlayer, perPlayerSe, meanChoice.linear().intercept(), meanChoice.model().rSquared(),
                n, (int) levels, realPlayers + (int) Math.floor(maxVirtual / f), proven, List.copyOf(notes),
                meanChoice.curved(), calibration == null ? 0 : f, List.copyOf(points), curve);
    }

    private record Crossing(Range range, boolean measured) {
    }

    /** Cost per player in the upper half of the levels is below 40% of the lower half. */
    static boolean flattening(Map<Integer, List<StageResult>> byLevel) {
        List<double[]> pts = new ArrayList<>();
        byLevel.forEach((v, list) -> pts.add(new double[]{v,
                Stats.median(list.stream().mapToDouble(st -> st.ticks().meanMs()).toArray())}));
        if (pts.size() < 5) {
            return false;
        }
        int mid = pts.size() / 2;
        double[] first = pts.getFirst();
        double[] middle = pts.get(mid);
        double[] last = pts.getLast();
        double early = (middle[1] - first[1]) / Math.max(1, middle[0] - first[0]);
        double late = (last[1] - middle[1]) / Math.max(1, last[0] - middle[0]);
        return early > 0 && late < early * 0.4;
    }

    /** Median crossing over the runs; counts as measured only if at least half of them crossed. */
    private static Crossing cross(FittedModel model, List<StageResult> stages, boolean useP95, double threshold,
                                  double t) {
        Range band = CapacitySolver.solve(model, threshold, t);
        Map<Integer, List<StageResult>> byRun = new TreeMap<>();
        stages.forEach(s -> byRun.computeIfAbsent(s.run(), k -> new ArrayList<>()).add(s));
        List<Double> crossings = new ArrayList<>();
        for (List<StageResult> run : byRun.values()) {
            List<StageResult> sorted = run.stream()
                    .sorted((a, b) -> Integer.compare(a.virtualPlayers(), b.virtualPlayers())).toList();
            double[] x = sorted.stream().mapToDouble(StageResult::virtualPlayers).toArray();
            double[] y = sorted.stream().mapToDouble(s -> useP95 ? s.ticks().p95Ms() : s.ticks().meanMs()).toArray();
            double c = CapacitySolver.interpolatedCrossing(x, y, threshold);
            if (!Double.isNaN(c)) {
                crossings.add(c);
            }
        }
        if (crossings.isEmpty() || crossings.size() * 2 < byRun.size()) {
            return new Crossing(band, false);
        }
        double measured = Stats.median(crossings.stream().mapToDouble(Double::doubleValue).toArray());
        return new Crossing(new Range(measured, Math.min(band.low(), measured), Math.max(band.high(), measured)),
                true);
    }
}

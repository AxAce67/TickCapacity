package sh.aki.tickcapacity.capacity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import sh.aki.tickcapacity.bench.BenchmarkResult;
import sh.aki.tickcapacity.bench.ResultRepository;
import sh.aki.tickcapacity.core.analysis.HourlyProfile;
import sh.aki.tickcapacity.core.estimate.ActivityAnalyzer;
import sh.aki.tickcapacity.core.estimate.BenchmarkCapacityEstimator;
import sh.aki.tickcapacity.core.estimate.Calibration;
import sh.aki.tickcapacity.core.estimate.CapacityEstimate;
import sh.aki.tickcapacity.core.estimate.Confidence;
import sh.aki.tickcapacity.core.estimate.ObservedCapacityEstimator;
import sh.aki.tickcapacity.core.estimate.Thresholds;
import sh.aki.tickcapacity.core.model.Sample;
import sh.aki.tickcapacity.monitor.HardwareInfo;
import sh.aki.tickcapacity.sampling.SampleRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Computes estimates from the stored data and caches them, so the live display and alerts never touch the
 * disk. {@link #compute} blocks and belongs on the I/O thread.
 */
public final class CapacityService {

    /** {@code benchmarkEstimate} is recomputed with the calibration when one applies. */
    public record Snapshot(CapacityEstimate observed, BenchmarkResult benchmark, CapacityEstimate benchmarkEstimate,
                           Calibration calibration, ActivityAnalyzer.Result activity, HourlyProfile hourly,
                           int samples, long computedAtEpochMs) {

        /** Real data if it is at least MEDIUM, else the benchmark, else low-confidence real data. */
        public Optional<CapacityEstimate> best() {
            if (observed != null && observed.confidence().compareTo(Confidence.MEDIUM) >= 0) {
                return Optional.of(observed);
            }
            if (benchmarkEstimate != null && benchmarkEstimate.confidence() != Confidence.INSUFFICIENT) {
                return Optional.of(benchmarkEstimate);
            }
            if (observed != null && observed.confidence() == Confidence.LOW) {
                return Optional.of(observed);
            }
            return Optional.empty();
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    /** Notes about how a benchmark was run; they must survive re-estimation. */
    private static final java.util.Set<String> RUN_NOTES =
            java.util.Set.of("entity-activation-range", "spawns-blocked", "moving-players");

    private final SampleRepository samples;
    private final ResultRepository results;
    private final Path calibrationFile;
    private final Logger logger;
    private volatile Snapshot snapshot;

    public CapacityService(SampleRepository samples, ResultRepository results, Path calibrationFile, Logger logger) {
        this.samples = samples;
        this.results = results;
        this.calibrationFile = calibrationFile;
        this.logger = logger;
    }

    /** Null until the first computation. */
    public Snapshot snapshot() {
        return snapshot;
    }

    /** @param current hardware now; a calibration is only derived from a benchmark on the same setup */
    public Snapshot compute(Thresholds thresholds, int historyDays, HardwareInfo current, boolean useCalibration) {
        List<Sample> data = samples.loadRecentDays(historyDays);
        CapacityEstimate observed = new ObservedCapacityEstimator(thresholds).estimate(data);
        ActivityAnalyzer.Result activity = new ActivityAnalyzer(thresholds).analyze(data);
        HourlyProfile hourly = HourlyProfile.of(data, ZoneId.systemDefault());

        BenchmarkResult bench = results.latestUsable().orElse(null);
        CapacityEstimate benchEstimate = bench == null ? null : bench.estimate();
        Calibration calibration = null;
        if (useCalibration && bench != null) {
            CapacityEstimate raw = new BenchmarkCapacityEstimator(thresholds).estimate(bench.stages(),
                    bench.realPlayers());
            if (comparable(bench.hardware(), current)) {
                Optional<Calibration> fresh = Calibration.derive(observed, raw, bench.id(), System.currentTimeMillis());
                fresh.ifPresent(this::saveCalibration);
            }
            calibration = loadCalibration().orElse(null);
            if (calibration != null) {
                benchEstimate = new BenchmarkCapacityEstimator(thresholds).estimate(bench.stages(),
                        bench.realPlayers(), calibration);
                for (CapacityEstimate.Note n : bench.estimate().notesOrEmpty()) {
                    if (RUN_NOTES.contains(n.key())) {
                        benchEstimate = benchEstimate.withNote(n);
                    }
                }
            }
        }
        Snapshot s = new Snapshot(observed, bench, benchEstimate, calibration, activity, hourly, data.size(),
                System.currentTimeMillis());
        snapshot = s;
        return s;
    }

    static boolean comparable(HardwareInfo a, HardwareInfo b) {
        return a != null && b != null
                && Objects.equals(a.cpuModel(), b.cpuModel())
                && a.availableProcessors() == b.availableProcessors()
                && a.simulationDistance() == b.simulationDistance()
                && a.viewDistance() == b.viewDistance();
    }

    public Optional<Calibration> loadCalibration() {
        if (!Files.isRegularFile(calibrationFile)) {
            return Optional.empty();
        }
        try {
            Calibration c = GSON.fromJson(Files.readString(calibrationFile, StandardCharsets.UTF_8), Calibration.class);
            return c != null && c.factor() > 0 ? Optional.of(c) : Optional.empty();
        } catch (IOException | JsonParseException e) {
            logger.log(Level.WARNING, "Could not read " + calibrationFile, e);
            return Optional.empty();
        }
    }

    private void saveCalibration(Calibration c) {
        try {
            Files.createDirectories(calibrationFile.getParent());
            Files.writeString(calibrationFile, GSON.toJson(c), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not write " + calibrationFile, e);
        }
    }

    public void clearCalibration() {
        try {
            Files.deleteIfExists(calibrationFile);
        } catch (IOException e) {
            logger.log(Level.WARNING, "Could not delete " + calibrationFile, e);
        }
    }
}

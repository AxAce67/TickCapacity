package sh.aki.tickcapacity.bench;

import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import sh.aki.tickcapacity.compat.ServerCompat;
import sh.aki.tickcapacity.config.Settings;
import sh.aki.tickcapacity.core.estimate.BenchmarkCapacityEstimator;
import sh.aki.tickcapacity.core.estimate.Calibration;
import sh.aki.tickcapacity.core.estimate.CapacityEstimate;
import sh.aki.tickcapacity.core.estimate.MemoryBudget;
import sh.aki.tickcapacity.core.estimate.StageResult;
import sh.aki.tickcapacity.core.estimate.StepPlanner;
import sh.aki.tickcapacity.core.estimate.Thresholds;
import sh.aki.tickcapacity.core.model.TickSummary;
import sh.aki.tickcapacity.display.DisplayService;
import sh.aki.tickcapacity.i18n.Messages;
import sh.aki.tickcapacity.monitor.ChunkGenerationCounter;
import sh.aki.tickcapacity.monitor.HardwareInfo;
import sh.aki.tickcapacity.monitor.JvmMetrics;
import sh.aki.tickcapacity.monitor.TickMonitor;
import sh.aki.tickcapacity.monitor.WorldCounts;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Runs the stepped load benchmark: measure a baseline, add virtual players, wait until the server settles,
 * measure, repeat until the MSPT limit, the memory limit or the configured maximum is reached.
 *
 * <p>Reaching a limit is the normal way for a run to end. Load that pushes the server over the edge while it
 * is being added is rolled back and a smaller step is tried; only an imminent out-of-memory stops everything
 * at once. All methods run on the main thread.
 */
public final class BenchmarkRunner {

    public enum Phase { IDLE, PREPARING, SETTLING, MEASURING }

    static final String ADMIN_PERMISSION = "tickcapacity.admin";

    private static final long SPAWN_TIMEOUT_NANOS = 20_000_000_000L;
    private static final long MAX_SETTLE_NANOS = 30_000_000_000L;
    private static final int RUN_OFFSET_CHUNKS = 2000;
    private static final int MAX_BACKOFFS = 2;
    private static final int MIN_TICKS_FOR_EARLY_STAGE = 100;

    // Memory: plan up to MEMORY_BUDGET of the heap; treat sustained high usage as the limit;
    // stop at once if the heap is practically full.
    private static final double MEMORY_BUDGET = 0.85;
    private static final double MEMORY_USED_LIMIT = 0.90;
    private static final double MEMORY_AFTER_GC_LIMIT = 0.80;
    private static final int MEMORY_SUSTAIN_SECONDS = 10;
    private static final double MEMORY_CRITICAL = 0.97;
    private static final int MEMORY_CRITICAL_SECONDS = 3;

    private static final DateTimeFormatter ID_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

    private final Plugin plugin;
    private final Server server;
    private final TickMonitor monitor;
    private final JvmMetrics jvm;
    private final ChunkGenerationCounter chunkCounter;
    private final ResultRepository results;
    private final Executor io;
    private final Supplier<HardwareInfo> hardware;
    private final Supplier<Calibration> calibration;
    private final Runnable onFinished;
    private final BenchmarkWorld benchWorld;
    private final BenchmarkUi ui;
    private Settings.Benchmark config;
    private Thresholds thresholds;

    // per benchmark
    private Phase phase = Phase.IDLE;
    private BukkitTask task;
    private LoadContext ctx;
    private TerrainLoader terrain;
    private boolean force;
    private UUID initiator;
    private long startedAtMs;
    private int realPlayers;
    private boolean earActive;
    private boolean moving;
    private int originalPauseSeconds;
    private int repetitions;
    private int currentRun;
    private boolean limitReached;
    private boolean memoryLimited;
    private int highMemorySeconds;
    private int criticalMemorySeconds;
    private final List<StageResult> stages = new ArrayList<>();

    // per run
    private List<int[]> layout = List.of();
    private final List<VirtualPlayer> players = new ArrayList<>();
    private final List<VirtualPlayer> pending = new ArrayList<>();
    private final List<MemoryBudget.Point> memoryPoints = new ArrayList<>();
    private int lastGoodPlayers;
    private int backoffs;
    private String endAfterStage;
    private double lastMeanMs;

    // per stage
    private boolean spawned;
    private long spawnedAtNanos;
    private long stageStartedNanos;
    private long phaseEndsAtNanos;
    private int lastChunkCount;
    private TickMonitor.Window window;
    private JvmMetrics.Counters stageStart;
    private long stageChunkStart;
    private double cpuSum;
    private int cpuSamples;

    public BenchmarkRunner(Plugin plugin, TickMonitor monitor, JvmMetrics jvm, ChunkGenerationCounter chunkCounter,
                           ResultRepository results, DisplayService displays, Executor io,
                           Supplier<HardwareInfo> hardware, Supplier<Calibration> calibration, Runnable onFinished,
                           Settings settings, Messages messages) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.monitor = monitor;
        this.jvm = jvm;
        this.chunkCounter = chunkCounter;
        this.results = results;
        this.io = io;
        this.hardware = hardware;
        this.calibration = calibration;
        this.onFinished = onFinished;
        this.benchWorld = new BenchmarkWorld(plugin, io);
        this.ui = new BenchmarkUi(server, displays, messages);
        reconfigure(settings, messages);
    }

    /** New settings apply to the next benchmark; a running one keeps its own. */
    public void reconfigure(Settings settings, Messages messages) {
        if (!isRunning()) {
            this.config = settings.benchmark();
            this.thresholds = settings.thresholds();
        }
        ui.setMessages(messages);
    }

    public boolean isRunning() {
        return phase != Phase.IDLE;
    }

    public Phase phase() {
        return phase;
    }

    public int activeVirtualPlayers() {
        return players.size();
    }

    public int completedStages() {
        return stages.size();
    }

    public int currentRun() {
        return currentRun;
    }

    public int repetitions() {
        return repetitions;
    }

    public int pregenPercent() {
        return terrain == null ? 100 : terrain.percent();
    }

    /** @return null if started, otherwise the message key of the reason it could not start */
    public String start(CommandSender sender, boolean force, int repetitions) {
        if (isRunning()) {
            return "bench.already-running";
        }
        if (benchWorld.isReleasing()) {
            return "bench.cleaning-up";
        }
        if (repetitions < 1 || repetitions > config.maxRepetitions()) {
            return "bench.bad-repetitions";
        }
        UUID starter = sender instanceof Player p ? p.getUniqueId() : null;
        if (config.requireEmptyServer() && !force && othersOnline(starter) > 0) {
            return "bench.players-online";
        }
        if (config.entitiesPerPlayer().isEmpty() && config.chunkRadius() == 0) {
            return "bench.nothing-to-do";
        }
        if (benchWorld.conflicts(config.worldName())) {
            return "bench.world-conflict";
        }

        ui.log("bench.preparing", config.worldName());
        World world;
        try {
            world = benchWorld.open(config.worldName(), config.seed());
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not create benchmark world", e);
            return "bench.world-failed";
        }
        if (world == null) {
            return "bench.world-conflict";
        }

        int radius = config.chunkRadius() < 0 ? Math.clamp(server.getSimulationDistance(), 2, 12)
                : config.chunkRadius();
        ctx = new LoadContext(plugin, world, config, radius);
        terrain = new TerrainLoader(ctx);
        this.force = force;
        this.initiator = starter;
        ui.setInitiator(starter);
        this.startedAtMs = System.currentTimeMillis();
        this.realPlayers = server.getOnlinePlayers().size();
        this.repetitions = repetitions;
        this.currentRun = 0;
        this.limitReached = false;
        this.memoryLimited = false;
        this.highMemorySeconds = 0;
        this.criticalMemorySeconds = 0;
        this.moving = config.movementBlocksPerSecond() > 0;
        this.earActive = ActivationRangeCheck.isActive(Path.of("spigot.yml"), config.worldName());
        stages.clear();
        if (earActive) {
            ui.log("bench.ear-warning", config.worldName());
        }

        // Servers that pause when nobody is online would stop ticking mid-benchmark.
        originalPauseSeconds = ServerCompat.pauseWhenEmptySeconds(server).orElse(Integer.MIN_VALUE);
        ServerCompat.setPauseWhenEmptySeconds(server, 0);
        task = server.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        startRun();
        return null;
    }

    /** Stops the benchmark and keeps what was measured so far. */
    public void cancel(String reasonKey) {
        if (isRunning()) {
            finish(BenchmarkResult.Status.CANCELLED, reasonKey, false);
        }
    }

    public void shutdown() {
        if (isRunning()) {
            finish(BenchmarkResult.Status.CANCELLED, "bench.reason.plugin-disabled", true);
        }
        benchWorld.releaseNow();
    }

    private void startRun() {
        int spacing = 2 * ctx.radius + 4;
        int cols = (int) Math.ceil(Math.sqrt(config.maxVirtualPlayers()));
        // With movement each run walks into fresh terrain.
        int offset = moving ? currentRun * RUN_OFFSET_CHUNKS : 0;
        List<int[]> homes = new ArrayList<>();
        for (int i = 0; i < config.maxVirtualPlayers(); i++) {
            homes.add(new int[]{offset + (i % cols) * spacing, (i / cols) * spacing});
        }
        layout = homes;
        pending.clear();
        memoryPoints.clear();
        lastGoodPlayers = 0;
        backoffs = 0;
        endAfterStage = null;
        lastMeanMs = Double.NaN;
        if (repetitions > 1) {
            ui.log("bench.run-start", currentRun + 1, repetitions);
        }
        beginSettling(); // the baseline needs no preparation
        updateDisplay();
    }

    private void prepareStage(int target) {
        phase = Phase.PREPARING;
        spawned = false;
        pending.clear();
        for (int i = players.size(); i < target; i++) {
            int[] home = layout.get(i);
            pending.add(new VirtualPlayer(ctx, i, currentRun, home[0], home[1]));
        }
        terrain.load(pending, pct -> ui.log("bench.pregen-progress", pct));
    }

    private void tick() {
        if (!checkLimits()) {
            return;
        }
        try {
            advance();
        } finally {
            if (isRunning()) {
                updateDisplay();
            }
        }
    }

    private void advance() {
        long now = System.nanoTime();
        if (moving) {
            players.forEach(vp -> vp.move(1.0));
        }
        switch (phase) {
            case PREPARING -> {
                if (!spawned && terrain.isDone()) {
                    pending.forEach(VirtualPlayer::spawnMobs);
                    players.addAll(pending);
                    pending.clear();
                    spawned = true;
                    spawnedAtNanos = now;
                } else if (spawned && (ctx.pendingSpawns == 0 || now - spawnedAtNanos > SPAWN_TIMEOUT_NANOS)) {
                    beginSettling();
                }
            }
            case SETTLING -> {
                int chunks = ctx.world.getChunkCount();
                boolean chunksSettled = chunks == lastChunkCount;
                lastChunkCount = chunks;
                boolean timedOut = now - stageStartedNanos > MAX_SETTLE_NANOS;
                if (now >= phaseEndsAtNanos && ((chunksSettled && monitor.isStable()) || timedOut)) {
                    if (timedOut) {
                        ui.log("bench.unstable-stage", realPlayers + players.size());
                    }
                    beginMeasuring(now);
                }
            }
            case MEASURING -> {
                double cpu = jvm.processCpu();
                if (!Double.isNaN(cpu)) {
                    cpuSum += cpu;
                    cpuSamples++;
                }
                if (now >= phaseEndsAtNanos) {
                    completeStage();
                }
            }
            case IDLE -> {
            }
        }
    }

    private void beginSettling() {
        phase = Phase.SETTLING;
        lastChunkCount = -1;
        stageStartedNanos = System.nanoTime();
        phaseEndsAtNanos = stageStartedNanos + config.settleSeconds() * 1_000_000_000L;
    }

    private void beginMeasuring(long now) {
        window = monitor.openWindow();
        stageStart = jvm.counters();
        stageChunkStart = chunkCounter.total();
        cpuSum = 0;
        cpuSamples = 0;
        phase = Phase.MEASURING;
        phaseEndsAtNanos = now + config.stageSeconds() * 1_000_000_000L;
    }

    private void completeStage() {
        TickSummary summary = window.summary();
        long durationMs = window.elapsedMillis();
        window.close();
        window = null;
        JvmMetrics.Counters end = jvm.counters();
        WorldCounts counts = WorldCounts.of(server);
        int active = players.size();
        stages.add(new StageResult(currentRun, active, summary, durationMs,
                cpuSamples > 0 ? cpuSum / cpuSamples : Double.NaN,
                end.gcTimeMs() - stageStart.gcTimeMs(), jvm.heapUsedMb(), counts.loadedChunks(), counts.entities(),
                (int) Math.min(Integer.MAX_VALUE, chunkCounter.total() - stageChunkStart)));
        lastMeanMs = summary.meanMs();
        lastGoodPlayers = active;
        double heapNow = retainedHeapMb();
        memoryPoints.add(new MemoryBudget.Point(active, heapNow));
        ui.log("bench.stage-done", realPlayers + active, active, BenchmarkUi.fmt(summary.meanMs()),
                BenchmarkUi.fmt(summary.p95Ms()), BenchmarkUi.fmt(summary.impliedTps()));

        if (endAfterStage != null) {
            String reason = endAfterStage;
            endAfterStage = null;
            endRun(reason, true);
            return;
        }
        if (summary.meanMs() >= thresholds.limitMspt()) {
            endRun("bench.reason.limit-reached", true);
            return;
        }
        if (active >= config.maxVirtualPlayers()) {
            endRun("bench.reason.completed", false);
            return;
        }
        List<StageResult> thisRun = stages.stream().filter(s -> s.run() == currentRun).toList();
        int target = StepPlanner.next(thisRun, active, config.maxVirtualPlayers(), config.playersPerStage(),
                thresholds.limitMspt());
        int affordable = MemoryBudget.maxAffordable(memoryPoints, active, heapNow, jvm.heapMaxMb(), MEMORY_BUDGET);
        if (affordable <= active) {
            memoryLimited = true;
            ui.log("bench.memory-budget", String.format(Locale.ROOT, "%.0f", MemoryBudget.mbPerPlayer(memoryPoints)),
                    realPlayers + active);
            endRun("bench.reason.memory-budget", true);
            return;
        }
        if (affordable < target) {
            ui.log("bench.memory-capped", realPlayers + target, realPlayers + affordable);
            target = affordable;
        }
        prepareStage(target);
    }

    /** @return false if this tick's normal progress should be skipped */
    private boolean checkLimits() {
        if (server.getWorld(ctx.world.getUID()) == null) {
            finish(BenchmarkResult.Status.ABORTED, "bench.reason.world-unloaded", false);
            return false;
        }
        if (config.requireEmptyServer() && !force && othersOnline(initiator) > 0) {
            finish(BenchmarkResult.Status.ABORTED, "bench.reason.player-joined", false);
            return false;
        }
        double used = jvm.heapUsedRatio();
        double afterGc = jvm.heapAfterGcRatio();
        criticalMemorySeconds = used >= MEMORY_CRITICAL ? criticalMemorySeconds + 1 : 0;
        if (criticalMemorySeconds >= MEMORY_CRITICAL_SECONDS) {
            logMemory("critical", used, afterGc);
            memoryLimited = true;
            finish(BenchmarkResult.Status.LIMIT_REACHED, "bench.reason.memory-limit", false);
            return false;
        }
        // A pool's after-GC figure can be stale, so it has to agree with current usage for a while.
        boolean high = used >= MEMORY_USED_LIMIT && (Double.isNaN(afterGc) || afterGc >= MEMORY_AFTER_GC_LIMIT);
        highMemorySeconds = high ? highMemorySeconds + 1 : 0;
        if (highMemorySeconds >= MEMORY_SUSTAIN_SECONDS) {
            logMemory("pressure", used, afterGc);
            highMemorySeconds = 0;
            memoryLimited = true;
            return hitLimit("bench.reason.memory-limit", false);
        }
        // Loading new chunks causes short spikes, so outside measurement only an extreme value counts.
        boolean measuring = phase == Phase.MEASURING;
        TickSummary recent = monitor.recent(measuring ? 5 : 10);
        double overloadAt = measuring ? config.abortMspt() : config.abortMspt() * 2;
        if (!recent.isEmpty() && recent.meanMs() > overloadAt) {
            return hitLimit("bench.reason.overload-limit", true);
        }
        return true;
    }

    private void logMemory(String kind, double used, double afterGc) {
        plugin.getLogger().warning(String.format(Locale.ROOT,
                "Benchmark memory %s: heap used %.0f%%, retained after GC %.0f%% of %d MB", kind, used * 100,
                afterGc * 100, jvm.heapMaxMb()));
    }

    private boolean hitLimit(String reasonKey, boolean retry) {
        if (phase == Phase.MEASURING && window != null && window.ticks() >= MIN_TICKS_FOR_EARLY_STAGE) {
            endAfterStage = reasonKey;
            completeStage();
            return false;
        }
        int attempted = players.size() + pending.size();
        rollBack();
        int smaller = lastGoodPlayers + (attempted - lastGoodPlayers) / 2;
        if (retry && backoffs < MAX_BACKOFFS && smaller > lastGoodPlayers) {
            backoffs++;
            ui.log("bench.backoff", realPlayers + attempted, realPlayers + smaller);
            prepareStage(smaller);
        } else {
            endRun(reasonKey, true);
        }
        return false;
    }

    /** Removes the load added since the last completed stage. */
    private void rollBack() {
        if (window != null) {
            window.close();
            window = null;
        }
        ctx.cancelPending();
        terrain.clear();
        pending.forEach(VirtualPlayer::deactivate);
        pending.clear();
        while (players.size() > lastGoodPlayers) {
            players.removeLast().deactivate();
        }
    }

    private void endRun(String reasonKey, boolean limit) {
        limitReached |= limit;
        if (currentRun + 1 < repetitions) {
            ui.log(reasonKey);
            ui.log("bench.run-done", currentRun + 1, repetitions);
            removeAllLoad();
            currentRun++;
            startRun();
        } else {
            finish(limitReached ? BenchmarkResult.Status.LIMIT_REACHED : BenchmarkResult.Status.COMPLETED,
                    reasonKey, false);
        }
    }

    private void removeAllLoad() {
        ctx.cancelPending();
        pending.clear();
        players.forEach(VirtualPlayer::removeMobs);
        players.clear();
        ctx.world.removePluginChunkTickets(plugin);
    }

    private double retainedHeapMb() {
        double afterGc = jvm.heapAfterGcRatio();
        return Double.isNaN(afterGc) ? jvm.heapUsedMb() : afterGc * jvm.heapMaxMb();
    }

    private void finish(BenchmarkResult.Status status, String reasonKey, boolean synchronous) {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (window != null) {
            window.close();
            window = null;
        }
        ui.hide();
        int playersAtEnd = players.size();
        removeAllLoad();
        phase = Phase.IDLE;
        if (originalPauseSeconds != Integer.MIN_VALUE) {
            ServerCompat.setPauseWhenEmptySeconds(server, originalPauseSeconds);
        }
        benchWorld.release(ctx.world, !config.keepWorld() || moving, synchronous);

        Map<String, Integer> entities = new LinkedHashMap<>();
        config.entitiesPerPlayer().forEach((k, v) -> entities.put(k.name(), v));
        BenchmarkResult.Parameters params = new BenchmarkResult.Parameters(config.worldName(), config.seed(),
                ctx.radius, config.playersPerStage(), config.maxVirtualPlayers(), config.settleSeconds(),
                config.stageSeconds(), entities, earActive, repetitions, config.movementBlocksPerSecond());

        BenchmarkCapacityEstimator estimator = new BenchmarkCapacityEstimator(thresholds);
        CapacityEstimate estimate = withRunNotes(estimator.estimate(stages, realPlayers));
        Calibration cal = calibration.get();
        CapacityEstimate calibrated = cal == null ? null
                : withRunNotes(estimator.estimate(stages, realPlayers, cal));

        String id = LocalDateTime.now().format(ID_FORMAT);
        BenchmarkResult result = new BenchmarkResult(BenchmarkResult.FORMAT_VERSION, id,
                plugin.getPluginMeta().getVersion(), startedAtMs, System.currentTimeMillis(), status,
                reasonKey, realPlayers, params, hardware.get(), List.copyOf(stages), estimate, calibrated);

        plugin.getLogger().info("Benchmark mobs spawned: " + ctx.spawnSucceeded + " of " + ctx.spawnRequested);
        ui.log(reasonKey, currentRun + 1, repetitions);
        ui.log("bench.finished", id, stages.size(), playersAtEnd);
        Runnable save = () -> {
            try {
                results.save(result);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save benchmark result " + id, e);
            }
        };
        if (synchronous) {
            save.run();
            return;
        }
        ui.announce(calibrated != null ? calibrated : estimate, reasonKey);
        if (calibrated != null) {
            ui.log("bench.calibrated-from", BenchmarkUi.fmt(cal.factor()), cal.benchmarkId());
        }
        io.execute(() -> {
            save.run();
            onFinished.run();
        });
    }

    private CapacityEstimate withRunNotes(CapacityEstimate e) {
        if (earActive) {
            e = e.withNote(CapacityEstimate.Note.of("entity-activation-range"));
        }
        if (ctx.spawnRequested > 0 && ctx.spawnSucceeded < ctx.spawnRequested * 0.9) {
            e = e.withNote(new CapacityEstimate.Note("spawns-blocked",
                    (double) ctx.spawnSucceeded / ctx.spawnRequested));
        }
        if (moving) {
            e = e.withNote(new CapacityEstimate.Note("moving-players", config.movementBlocksPerSecond()));
        }
        if (memoryLimited) {
            e = e.withNote(new CapacityEstimate.Note("memory-limited", jvm.heapMaxMb()));
        }
        return e;
    }

    private int othersOnline(UUID except) {
        int n = 0;
        for (Player p : server.getOnlinePlayers()) {
            if (!p.getUniqueId().equals(except)) {
                n++;
            }
        }
        return n;
    }

    private void updateDisplay() {
        // A run ends at the MSPT limit or at max players, whichever comes first.
        double byPlayers = (double) players.size() / config.maxVirtualPlayers();
        double byLoad = Double.isNaN(lastMeanMs) ? 0 : lastMeanMs / thresholds.limitMspt();
        float progress = (float) ((currentRun + Math.min(1, Math.max(byPlayers, byLoad))) / repetitions);
        String run = ui.runLabel(currentRun + 1, repetitions);
        if (phase == Phase.PREPARING) {
            ui.showPreparing(config.progressDisplay(), terrain.percent(),
                    realPlayers + players.size() + pending.size(), progress, run);
        } else {
            TickSummary recent = monitor.recent(5);
            ui.showRunning(config.progressDisplay(), ui.phaseLabel(phase), realPlayers + players.size(),
                    recent.isEmpty() ? 0 : recent.meanMs(), thresholds, progress, run);
        }
    }
}

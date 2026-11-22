package sh.aki.tickcapacity.sampling;

import org.bukkit.Server;
import sh.aki.tickcapacity.core.model.Sample;
import sh.aki.tickcapacity.core.model.TickSummary;
import sh.aki.tickcapacity.monitor.ChunkGenerationCounter;
import sh.aki.tickcapacity.monitor.JvmMetrics;
import sh.aki.tickcapacity.monitor.TickMonitor;
import sh.aki.tickcapacity.monitor.WorldCounts;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;

/**
 * Turns the tick stream into samples. Nothing is recorded while the server is paused, right after startup or
 * during a benchmark, whose synthetic load would distort the players/MSPT relation.
 */
public final class Sampler implements Runnable {

    private static final int RECENT_CAPACITY = 360;

    private final Server server;
    private final TickMonitor ticks;
    private final JvmMetrics jvm;
    private final ChunkGenerationCounter chunkCounter;
    private final SampleRepository repository;
    private final Executor io;
    private final BooleanSupplier suppressed;
    private final long graceUntilNanos;
    private final boolean persist;
    private final Deque<Sample> recent = new ArrayDeque<>();

    private TickMonitor.Window window;
    private JvmMetrics.Counters last;
    private long lastChunkTotal;

    public Sampler(Server server, TickMonitor ticks, JvmMetrics jvm, ChunkGenerationCounter chunkCounter,
                   SampleRepository repository, Executor io,
                   BooleanSupplier suppressed, int startupGraceSeconds, boolean persist) {
        this.server = server;
        this.ticks = ticks;
        this.jvm = jvm;
        this.chunkCounter = chunkCounter;
        this.repository = repository;
        this.io = io;
        this.suppressed = suppressed;
        this.persist = persist;
        // Measured from JVM start so a reload does not restart the grace period.
        long uptimeMs = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime();
        long remainingMs = Math.max(0, startupGraceSeconds * 1000L - uptimeMs);
        this.graceUntilNanos = System.nanoTime() + remainingMs * 1_000_000L;
        restart();
    }

    @Override
    public void run() {
        if (sh.aki.tickcapacity.compat.ServerCompat.isPaused(server) || suppressed.getAsBoolean() || System.nanoTime() < graceUntilNanos) {
            restart();
            return;
        }
        JvmMetrics.Counters now = jvm.counters();
        TickSummary summary = window.summary();
        long intervalMs = (now.nanoTime() - last.nanoTime()) / 1_000_000;
        if (intervalMs <= 0 || summary.isEmpty()) {
            restart();
            return;
        }
        double tps = Math.min(20.0, summary.ticks() * 1000.0 / intervalMs);
        double mainCpuMs = now.mainThreadCpuNanos() >= 0 && last.mainThreadCpuNanos() >= 0
                ? (now.mainThreadCpuNanos() - last.mainThreadCpuNanos()) / 1e6 : Double.NaN;
        WorldCounts counts = WorldCounts.of(server);
        Sample sample = new Sample(System.currentTimeMillis(), intervalMs, server.getOnlinePlayers().size(), summary,
                tps, jvm.processCpu(), jvm.systemCpu(), mainCpuMs, jvm.heapUsedMb(), jvm.heapMaxMb(),
                now.gcCount() - last.gcCount(), now.gcTimeMs() - last.gcTimeMs(),
                counts.loadedChunks(), counts.entities(), counts.tickingBlockEntities(),
                (int) Math.min(Integer.MAX_VALUE, chunkCounter.total() - lastChunkTotal));

        recent.addLast(sample);
        while (recent.size() > RECENT_CAPACITY) {
            recent.removeFirst();
        }
        if (persist) {
            io.execute(() -> repository.append(sample));
        }
        restart();
    }

    public List<Sample> recent(int minutes) {
        long cutoff = System.currentTimeMillis() - minutes * 60_000L;
        List<Sample> out = new ArrayList<>();
        for (Sample s : recent) {
            if (s.epochMillis() >= cutoff) {
                out.add(s);
            }
        }
        return out;
    }

    public void close() {
        if (window != null) {
            window.close();
        }
    }

    private void restart() {
        if (window != null) {
            window.close();
        }
        window = ticks.openWindow();
        last = jvm.counters();
        lastChunkTotal = chunkCounter.total();
    }
}

package sh.aki.tickcapacity.monitor;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import sh.aki.tickcapacity.core.model.TickSummary;
import sh.aki.tickcapacity.core.stats.DoubleList;
import sh.aki.tickcapacity.core.stats.DoubleRingBuffer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Tick durations from Paper's tick event. One array store per tick and open window. Main thread only. */
public final class TickMonitor implements Listener {

    private static final int HISTORY = 1200;

    private final DoubleRingBuffer history = new DoubleRingBuffer(HISTORY);
    private final DoubleRingBuffer tickEndNanos = new DoubleRingBuffer(HISTORY);
    private final List<Window> windows = new CopyOnWriteArrayList<>();

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTickEnd(ServerTickEndEvent event) {
        double ms = event.getTickDuration();
        history.add(ms);
        tickEndNanos.add(System.nanoTime());
        for (Window w : windows) {
            w.durations.add(ms);
        }
    }

    public TickSummary recent(int seconds) {
        return TickSummary.of(history.latest(Math.max(1, seconds) * 20));
    }

    /** Measured from tick timestamps, so delayed ticks count. */
    public double tps(int seconds) {
        double[] t = tickEndNanos.latest(Math.max(2, seconds * 20 + 1));
        if (t.length < 2) {
            return Double.NaN;
        }
        double elapsedSec = (System.nanoTime() - t[0]) / 1e9;
        if (elapsedSec <= 0) {
            return Double.NaN;
        }
        return Math.min(20.0, (t.length - 1) / elapsedSec);
    }

    /**
     * The last 3 seconds look steady: per-second averages within 30% of each other and no spike. Used to
     * wait out chunk loading before measuring.
     */
    public boolean isStable() {
        double[] t = history.latest(60);
        if (t.length < 60) {
            return false;
        }
        double[] means = new double[3];
        for (int s = 0; s < 3; s++) {
            double sum = 0;
            for (int i = s * 20; i < s * 20 + 20; i++) {
                sum += t[i];
            }
            means[s] = sum / 20;
        }
        double min = Math.min(means[0], Math.min(means[1], means[2]));
        double max = Math.max(means[0], Math.max(means[1], means[2]));
        double[] sorted = t.clone();
        java.util.Arrays.sort(sorted);
        double median = sorted[30];
        boolean noSpike = sorted[59] <= Math.max(60, median * 4);
        return noSpike && (max <= 2 || max <= min * 1.3);
    }

    public long nanosSinceLastTick() {
        double[] last = tickEndNanos.latest(1);
        return last.length == 0 ? Long.MAX_VALUE : System.nanoTime() - (long) last[0];
    }

    public Window openWindow() {
        Window w = new Window(this);
        windows.add(w);
        return w;
    }

    public static final class Window implements AutoCloseable {
        private final TickMonitor owner;
        private final DoubleList durations = new DoubleList(1024);
        private final long startNanos = System.nanoTime();

        private Window(TickMonitor owner) {
            this.owner = owner;
        }

        public TickSummary summary() {
            return TickSummary.of(durations.toArray());
        }

        public int ticks() {
            return durations.size();
        }

        public long elapsedMillis() {
            return (System.nanoTime() - startNanos) / 1_000_000;
        }

        @Override
        public void close() {
            owner.windows.remove(this);
        }
    }
}

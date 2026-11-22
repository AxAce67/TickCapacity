package sh.aki.tickcapacity.monitor;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadMXBean;
import java.util.List;

/** Values the platform cannot provide come back as NaN or -1. */
public final class JvmMetrics {

    public record Counters(long nanoTime, long gcCount, long gcTimeMs, long mainThreadCpuNanos) {
    }

    private final MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final List<GarbageCollectorMXBean> pauseCollectors;
    private final com.sun.management.OperatingSystemMXBean os;
    private final long mainThreadId;
    private final boolean threadCpuSupported;

    /** Call from the main thread. */
    public JvmMetrics(Thread mainThread) {
        this.mainThreadId = mainThread.threadId();
        boolean supported = threads.isThreadCpuTimeSupported();
        if (supported && !threads.isThreadCpuTimeEnabled()) {
            try {
                threads.setThreadCpuTimeEnabled(true);
            } catch (UnsupportedOperationException | SecurityException e) {
                supported = false;
            }
        }
        this.threadCpuSupported = supported;
        this.os = ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean b
                ? b : null;
        this.pauseCollectors = pauseCollectors(ManagementFactory.getGarbageCollectorMXBeans());
    }

    /**
     * ZGC and Shenandoah report concurrent work in their "Cycles" beans; only the "Pauses" beans stall the
     * main thread.
     */
    static List<GarbageCollectorMXBean> pauseCollectors(List<GarbageCollectorMXBean> all) {
        List<GarbageCollectorMXBean> pauses = all.stream().filter(b -> b.getName().contains("Pauses")).toList();
        return pauses.isEmpty() ? List.copyOf(all) : pauses;
    }

    public Counters counters() {
        long count = 0;
        long time = 0;
        for (GarbageCollectorMXBean gc : pauseCollectors) {
            count += Math.max(0, gc.getCollectionCount());
            time += Math.max(0, gc.getCollectionTime());
        }
        long cpu = threadCpuSupported ? threads.getThreadCpuTime(mainThreadId) : -1;
        return new Counters(System.nanoTime(), count, time, cpu);
    }

    public long heapUsedMb() {
        return memory.getHeapMemoryUsage().getUsed() / (1024 * 1024);
    }

    public long heapMaxMb() {
        long max = memory.getHeapMemoryUsage().getMax();
        return max < 0 ? Runtime.getRuntime().maxMemory() / (1024 * 1024) : max / (1024 * 1024);
    }

    /** Heap retained after the last GC, as a fraction of the maximum. NaN if not reported. */
    public double heapAfterGcRatio() {
        long used = 0;
        boolean any = false;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() != MemoryType.HEAP || !pool.isCollectionUsageThresholdSupported()) {
                continue;
            }
            MemoryUsage after = pool.getCollectionUsage();
            if (after != null) {
                used += after.getUsed();
                any = true;
            }
        }
        long max = heapMaxMb() * 1024 * 1024;
        return any && max > 0 ? (double) used / max : Double.NaN;
    }

    public double heapUsedRatio() {
        long max = heapMaxMb();
        return max > 0 ? (double) heapUsedMb() / max : Double.NaN;
    }

    public double processCpu() {
        if (os == null) {
            return Double.NaN;
        }
        double v = os.getProcessCpuLoad();
        return v < 0 ? Double.NaN : v;
    }

    public double systemCpu() {
        if (os == null) {
            return Double.NaN;
        }
        double v = os.getCpuLoad();
        return v < 0 ? Double.NaN : v;
    }

    public long totalPhysicalMemoryMb() {
        return os == null ? -1 : os.getTotalMemorySize() / (1024 * 1024);
    }

    public long freePhysicalMemoryMb() {
        return os == null ? -1 : os.getFreeMemorySize() / (1024 * 1024);
    }

    public List<String> collectorNames() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().map(GarbageCollectorMXBean::getName).toList();
    }
}

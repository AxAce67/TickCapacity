package sh.aki.tickcapacity.core.analysis;

import sh.aki.tickcapacity.core.analysis.Finding.Category;
import sh.aki.tickcapacity.core.analysis.Finding.Severity;
import sh.aki.tickcapacity.core.model.Sample;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Simple threshold rules over recent samples. Findings come back most severe first. */
public final class BottleneckAnalyzer {

    public record Context(int availableProcessors, long maxHeapMb, int viewDistance, int simulationDistance,
                          double comfortableMspt, double limitMspt) {
    }

    public List<Finding> analyze(List<Sample> recent, Context ctx) {
        List<Finding> findings = new ArrayList<>();
        hardwareRules(ctx, findings);

        List<Sample> usable = recent.stream().filter(s -> s.ticks().ticks() > 0).toList();
        if (usable.isEmpty()) {
            findings.add(new Finding(Category.HEALTHY, Severity.INFO, "no-data", List.of()));
            return sorted(findings);
        }

        double wallMs = 0, busyMs = 0, ticks = 0, gcMs = 0, mainCpuMs = 0, mainCpuBusyMs = 0;
        double sysCpuSum = 0, heapRatioSum = 0, chunks = 0, entities = 0, blockEntities = 0, players = 0;
        int sysCpuN = 0;
        double worstP99 = 0;
        for (Sample s : usable) {
            wallMs += s.intervalMs();
            busyMs += s.ticks().busyMs();
            ticks += s.ticks().ticks();
            gcMs += s.gcTimeMs();
            if (!Double.isNaN(s.mainThreadCpuMs())) {
                mainCpuMs += s.mainThreadCpuMs();
                mainCpuBusyMs += s.ticks().busyMs();
            }
            if (!Double.isNaN(s.systemCpu())) {
                sysCpuSum += s.systemCpu();
                sysCpuN++;
            }
            if (s.heapMaxMb() > 0) {
                heapRatioSum += (double) s.heapUsedMb() / s.heapMaxMb();
            }
            chunks += s.loadedChunks();
            entities += s.entities();
            blockEntities += s.tickingBlockEntities();
            players += s.players();
            worstP99 = Math.max(worstP99, s.ticks().p99Ms());
        }
        int n = usable.size();
        double mspt = busyMs / ticks;
        double gcFraction = wallMs > 0 ? gcMs / wallMs : 0;
        double heapRatio = heapRatioSum / n;
        double avgChunks = chunks / n;
        double avgEntities = entities / n;
        double avgBlockEntities = blockEntities / n;
        double avgPlayers = players / n;
        double sysCpu = sysCpuN > 0 ? sysCpuSum / sysCpuN : Double.NaN;
        boolean loaded = mspt >= ctx.comfortableMspt();

        if (mspt >= ctx.limitMspt()) {
            findings.add(new Finding(Category.MAIN_THREAD, Severity.CRITICAL, "mspt-over-limit",
                    List.of(f1(mspt), f1(ctx.limitMspt()))));
        } else if (loaded) {
            findings.add(new Finding(Category.MAIN_THREAD, Severity.WARNING, "mspt-high",
                    List.of(f1(mspt), f1(ctx.comfortableMspt()))));
        }

        // Low CPU time during slow ticks means the main thread was waiting, not computing.
        if (loaded && mainCpuBusyMs > 0) {
            double cpuShare = mainCpuMs / mainCpuBusyMs;
            if (cpuShare < 0.7) {
                findings.add(new Finding(Category.CPU, Severity.WARNING, "main-thread-waiting",
                        List.of(pct(cpuShare))));
            }
        }

        if (!Double.isNaN(sysCpu) && sysCpu >= 0.9) {
            findings.add(new Finding(Category.CPU, loaded ? Severity.CRITICAL : Severity.WARNING,
                    "system-cpu-saturated", List.of(pct(sysCpu))));
        }

        if (gcFraction >= 0.10) {
            findings.add(new Finding(Category.GC, Severity.CRITICAL, "gc-heavy", List.of(pct(gcFraction))));
        } else if (gcFraction >= 0.05) {
            findings.add(new Finding(Category.GC, Severity.WARNING, "gc-heavy", List.of(pct(gcFraction))));
        }
        if (heapRatio >= 0.90) {
            findings.add(new Finding(Category.MEMORY, Severity.CRITICAL, "heap-pressure", List.of(pct(heapRatio))));
        } else if (heapRatio >= 0.80) {
            findings.add(new Finding(Category.MEMORY, Severity.WARNING, "heap-pressure", List.of(pct(heapRatio))));
        }

        if (worstP99 >= ctx.limitMspt() && worstP99 >= 3 * mspt) {
            findings.add(new Finding(Category.SPIKES, Severity.WARNING, "lag-spikes",
                    List.of(f1(worstP99), f1(mspt))));
        }

        double entitiesPerChunk = avgChunks > 0 ? avgEntities / avgChunks : 0;
        if (avgEntities >= 3000 || entitiesPerChunk >= 4) {
            findings.add(new Finding(Category.ENTITIES, loaded ? Severity.WARNING : Severity.INFO, "many-entities",
                    List.of(f0(avgEntities), f1(entitiesPerChunk))));
        }
        if (avgBlockEntities >= 2000) {
            findings.add(new Finding(Category.BLOCK_ENTITIES, loaded ? Severity.WARNING : Severity.INFO,
                    "many-block-entities", List.of(f0(avgBlockEntities))));
        }
        if (avgPlayers >= 1) {
            double perPlayerArea = Math.pow(2 * ctx.viewDistance() + 1, 2);
            double chunksPerPlayer = avgChunks / avgPlayers;
            if (chunksPerPlayer > perPlayerArea * 1.5) {
                findings.add(new Finding(Category.CHUNKS, Severity.INFO, "many-chunks-per-player",
                        List.of(f0(chunksPerPlayer), f0(perPlayerArea))));
            }
        }
        if (loaded && ctx.simulationDistance() > 6) {
            findings.add(new Finding(Category.CHUNKS, Severity.INFO, "reduce-simulation-distance",
                    List.of(Integer.toString(ctx.simulationDistance()))));
        }

        if (findings.stream().noneMatch(fd -> fd.severity() != Severity.INFO)) {
            findings.add(new Finding(Category.HEALTHY, Severity.INFO, "healthy", List.of(f1(mspt))));
        }
        return sorted(findings);
    }

    private static void hardwareRules(Context ctx, List<Finding> findings) {
        if (ctx.availableProcessors() < 2) {
            findings.add(new Finding(Category.HARDWARE, Severity.WARNING, "few-cores",
                    List.of(Integer.toString(ctx.availableProcessors()))));
        }
        if (ctx.maxHeapMb() > 0 && ctx.maxHeapMb() < 2048) {
            findings.add(new Finding(Category.HARDWARE, Severity.WARNING, "small-heap",
                    List.of(Long.toString(ctx.maxHeapMb()))));
        }
    }

    private static List<Finding> sorted(List<Finding> findings) {
        findings.sort(Comparator.comparing(Finding::severity).reversed());
        return List.copyOf(findings);
    }

    private static String f0(double v) {
        return String.format(Locale.ROOT, "%.0f", v);
    }

    private static String f1(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String pct(double ratio) {
        return String.format(Locale.ROOT, "%.0f%%", ratio * 100);
    }
}

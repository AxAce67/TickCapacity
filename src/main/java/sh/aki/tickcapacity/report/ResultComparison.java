package sh.aki.tickcapacity.report;

import net.kyori.adventure.text.Component;
import sh.aki.tickcapacity.bench.BenchmarkResult;
import sh.aki.tickcapacity.core.estimate.StageResult;
import sh.aki.tickcapacity.i18n.Messages;
import sh.aki.tickcapacity.monitor.HardwareInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class ResultComparison {

    private ResultComparison() {
    }

    public static List<Component> compare(Messages m, BenchmarkResult a, BenchmarkResult b) {
        List<Component> out = new ArrayList<>();
        out.add(m.get("compare.header", a.id(), b.id()));
        out.add(row(m, "compare.recommended", EstimateFormatter.range(a.estimate().recommended()),
                EstimateFormatter.range(b.estimate().recommended()),
                delta(a.estimate().recommended().point(), b.estimate().recommended().point())));
        out.add(row(m, "compare.limit", EstimateFormatter.range(a.estimate().limit()),
                EstimateFormatter.range(b.estimate().limit()),
                delta(a.estimate().limit().point(), b.estimate().limit().point())));
        out.add(row(m, "compare.per-player", f2(a.estimate().perPlayerMs()), f2(b.estimate().perPlayerMs()),
                delta(a.estimate().perPlayerMs(), b.estimate().perPlayerMs())));
        double baseA = baseline(a);
        double baseB = baseline(b);
        out.add(row(m, "compare.baseline", f2(baseA), f2(baseB), delta(baseA, baseB)));
        out.add(row(m, "compare.confidence", m.plain("confidence." + a.estimate().confidence().name()),
                m.plain("confidence." + b.estimate().confidence().name()), ""));

        HardwareInfo ha = a.hardware();
        HardwareInfo hb = b.hardware();
        if (!Objects.equals(ha.cpuModel(), hb.cpuModel()) || ha.availableProcessors() != hb.availableProcessors()) {
            out.add(row(m, "compare.cpu", ha.cpuModel() + " x" + ha.availableProcessors(),
                    hb.cpuModel() + " x" + hb.availableProcessors(), ""));
        }
        if (ha.maxHeapMb() != hb.maxHeapMb()) {
            out.add(row(m, "compare.heap", ha.maxHeapMb() + " MB", hb.maxHeapMb() + " MB", ""));
        }
        if (!Objects.equals(ha.javaVersion(), hb.javaVersion())) {
            out.add(row(m, "compare.java", ha.javaVersion(), hb.javaVersion(), ""));
        }
        if (!Objects.equals(ha.serverVersion(), hb.serverVersion())) {
            out.add(row(m, "compare.server", ha.serverVersion(), hb.serverVersion(), ""));
        }
        if (ha.viewDistance() != hb.viewDistance() || ha.simulationDistance() != hb.simulationDistance()) {
            out.add(row(m, "compare.distance", ha.viewDistance() + "/" + ha.simulationDistance(),
                    hb.viewDistance() + "/" + hb.simulationDistance(), ""));
        }
        BenchmarkResult.Parameters pa = a.parameters();
        BenchmarkResult.Parameters pb = b.parameters();
        if (pa.chunkRadius() != pb.chunkRadius() || pa.seed() != pb.seed()
                || !pa.entitiesPerPlayer().equals(pb.entitiesPerPlayer())
                || pa.stageSeconds() != pb.stageSeconds()) {
            out.add(m.get("compare.params-differ"));
        }
        return out;
    }

    private static double baseline(BenchmarkResult r) {
        return r.stages().stream().filter(s -> s.virtualPlayers() == 0).findFirst()
                .map(StageResult::ticks).map(t -> t.meanMs()).orElse(Double.NaN);
    }

    private static Component row(Messages m, String labelKey, String a, String b, String delta) {
        return m.get("compare.row", m.plain(labelKey), a, b, delta);
    }

    private static String delta(double a, double b) {
        if (!Double.isFinite(a) || !Double.isFinite(b) || a == 0) {
            return "";
        }
        return String.format(Locale.ROOT, "%+.0f%%", (b - a) / Math.abs(a) * 100);
    }

    private static String f2(double v) {
        return EstimateFormatter.f2(v);
    }
}

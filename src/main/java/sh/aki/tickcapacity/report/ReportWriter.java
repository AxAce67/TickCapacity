package sh.aki.tickcapacity.report;

import sh.aki.tickcapacity.bench.BenchmarkResult;
import sh.aki.tickcapacity.capacity.CapacityService;
import sh.aki.tickcapacity.core.analysis.Finding;
import sh.aki.tickcapacity.core.analysis.HourlyProfile;
import sh.aki.tickcapacity.core.estimate.CapacityEstimate;
import sh.aki.tickcapacity.core.estimate.StageResult;
import sh.aki.tickcapacity.core.model.TickSummary;
import sh.aki.tickcapacity.i18n.Messages;
import sh.aki.tickcapacity.monitor.HardwareInfo;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Blocking; the data is captured on the main thread beforehand. */
public final class ReportWriter {

    public record ReportData(
            String pluginVersion,
            HardwareInfo hardware,
            TickSummary last60s,
            double tps60s,
            int playersOnline,
            CapacityService.Snapshot snapshot,
            List<Finding> findings,
            boolean sparkPresent,
            sh.aki.tickcapacity.core.estimate.Thresholds thresholds
    ) {
    }

    public record Written(Path markdown, Path html) {
    }

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");

    private ReportWriter() {
    }

    public static Written write(Path dir, ReportData d, Messages m) throws IOException {
        Files.createDirectories(dir);
        LocalDateTime now = LocalDateTime.now();
        String base = "report-" + now.format(FILE_TS);
        Path md = dir.resolve(base + ".md");
        Path html = dir.resolve(base + ".html");
        Files.writeString(md, render(d, m, now), StandardCharsets.UTF_8);
        Files.writeString(html, HtmlReport.render(d, m, now), StandardCharsets.UTF_8);
        return new Written(md, html);
    }

    static String render(ReportData d, Messages m, LocalDateTime now) {
        CapacityService.Snapshot s = d.snapshot();
        StringBuilder sb = new StringBuilder(8192);
        sb.append("# ").append(m.plain("report.title")).append("\n\n");
        sb.append(m.plain("report.generated", now.withNano(0).toString().replace('T', ' '), d.pluginVersion()))
                .append("\n\n");

        sb.append("## ").append(m.plain("report.section.capacity")).append("\n\n");
        bullets(sb, EstimateFormatter.plainLines(m, s.observed()));
        if (s.benchmarkEstimate() != null) {
            sb.append('\n');
            bullets(sb, EstimateFormatter.plainLines(m, s.benchmarkEstimate()));
            sb.append("- ").append(m.plain("report.benchmark-id", s.benchmark().id(), s.benchmark().status().name()))
                    .append('\n');
        }
        if (s.calibration() != null) {
            sb.append("- ").append(InsightFormatter.calibrationPlain(m, s.calibration())).append('\n');
        }
        sb.append('\n').append("> ").append(m.plain("report.capacity-disclaimer")).append("\n\n");

        List<String> activity = InsightFormatter.activityPlain(m, s.activity());
        sb.append("## ").append(activity.getFirst()).append("\n\n");
        bullets(sb, activity.subList(1, activity.size()));
        sb.append('\n');

        HourlyProfile hourly = s.hourly();
        sb.append("## ").append(m.plain("report.section.hourly")).append("\n\n");
        if (hourly.peakPlayers().isEmpty()) {
            sb.append(m.plain("peak.no-data")).append("\n\n");
        } else {
            sb.append("| h | ").append(m.plain("report.players")).append(" (avg / max) | MSPT median | p95 | samples |\n");
            sb.append("|---|---|---|---|---|\n");
            for (HourlyProfile.Hour h : hourly.hours()) {
                if (h.samples() > 0) {
                    sb.append("| ").append(h.hour()).append(" | ").append(f1(h.avgPlayers())).append(" / ")
                            .append(h.maxPlayers()).append(" | ").append(f1(h.medianMspt())).append(" | ")
                            .append(f1(h.medianP95())).append(" | ").append(h.samples()).append(" |\n");
                }
            }
            sb.append('\n');
        }

        sb.append("## ").append(m.plain("report.section.bottlenecks")).append("\n\n");
        for (Finding f : d.findings()) {
            sb.append("- **").append(m.plain("severity." + f.severity().name())).append("** ")
                    .append(m.plain("finding." + f.key(), f.args().toArray()));
            String advice = "advice." + f.key();
            if (m.has(advice)) {
                sb.append("\n  - ").append(m.plain(advice, f.args().toArray()));
            }
            sb.append('\n');
        }
        if (d.sparkPresent()) {
            sb.append("- ").append(m.plain("analyze.spark-hint")).append('\n');
        }
        sb.append('\n');

        sb.append("## ").append(m.plain("report.section.current")).append("\n\n");
        TickSummary t = d.last60s();
        sb.append("| TPS (60s) | MSPT mean | p50 | p95 | p99 | max | ").append(m.plain("report.players")).append(" |\n");
        sb.append("|---|---|---|---|---|---|---|\n");
        sb.append("| ").append(f1(d.tps60s())).append(" | ").append(f1(t.meanMs())).append(" | ").append(f1(t.p50Ms()))
                .append(" | ").append(f1(t.p95Ms())).append(" | ").append(f1(t.p99Ms())).append(" | ")
                .append(f1(t.maxMs())).append(" | ").append(d.playersOnline()).append(" |\n\n");

        BenchmarkResult b = s.benchmark();
        if (b != null && !b.stages().isEmpty()) {
            sb.append("## ").append(m.plain("report.section.stages", b.id())).append("\n\n");
            sb.append("| run | ").append(m.plain("report.virtual-players"))
                    .append(" | MSPT mean | p95 | p99 | max | TPS | CPU | GC ms | chunks | new chunks | entities |\n");
            sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
            for (StageResult st : b.stages()) {
                TickSummary ts = st.ticks();
                sb.append("| ").append(st.run() + 1).append(" | ").append(st.virtualPlayers()).append(" | ")
                        .append(f1(ts.meanMs())).append(" | ").append(f1(ts.p95Ms())).append(" | ")
                        .append(f1(ts.p99Ms())).append(" | ").append(f1(ts.maxMs())).append(" | ")
                        .append(f1(ts.impliedTps())).append(" | ").append(pct(st.processCpu())).append(" | ")
                        .append(st.gcTimeMs()).append(" | ").append(st.loadedChunks()).append(" | ")
                        .append(st.newChunks()).append(" | ").append(st.entities()).append(" |\n");
            }
            BenchmarkResult.Parameters p = b.parameters();
            sb.append('\n').append(m.plain("report.bench-params", p.chunkRadius(), p.playersPerStage(),
                    p.settleSeconds(), p.stageSeconds(), p.seed(), p.entitiesPerPlayer().toString(),
                    Math.max(1, p.repetitions()), f1(p.movementBlocksPerSecond()))).append("\n\n");
        }

        HardwareInfo h = d.hardware();
        sb.append("## ").append(m.plain("report.section.hardware")).append("\n\n");
        sb.append("| | |\n|---|---|\n");
        for (String[] row : hardwareRows(h)) {
            sb.append("| ").append(row[0]).append(" | ").append(row[1].replace("|", "\\|")).append(" |\n");
        }
        sb.append('\n');

        sb.append("## ").append(m.plain("report.section.method")).append("\n\n");
        sb.append(m.plain("report.method")).append('\n');
        return sb.toString();
    }

    static List<String[]> hardwareRows(HardwareInfo h) {
        return List.of(
                new String[]{"CPU", h.cpuModel() + " (" + h.availableProcessors() + " threads)"},
                new String[]{"RAM", h.physicalMemoryMb() < 0 ? "?" : h.physicalMemoryMb() + " MB"},
                new String[]{"Max heap", h.maxHeapMb() + " MB"},
                new String[]{"OS", h.osName() + " " + h.osVersion() + " (" + h.osArch() + ")"},
                new String[]{"Java", h.javaVersion() + " — " + h.vmName() + " (" + h.javaVendor() + ")"},
                new String[]{"GC", String.join(", ", h.garbageCollectors())},
                new String[]{"JVM flags", h.jvmFlags().isEmpty() ? "-" : String.join(" ", h.jvmFlags())},
                new String[]{"Server", h.serverVersion()},
                new String[]{"view / simulation distance", h.viewDistance() + " / " + h.simulationDistance()},
                new String[]{"Disk free", h.diskFreeMb() + " MB"});
    }

    static boolean hasChartData(CapacityEstimate e) {
        return e != null && !e.pointsOrEmpty().isEmpty();
    }

    private static void bullets(StringBuilder sb, List<String> lines) {
        for (String l : lines) {
            sb.append("- ").append(l).append('\n');
        }
    }

    static String pct(double v) {
        return Double.isNaN(v) ? "?" : String.format(Locale.ROOT, "%.0f%%", v * 100);
    }

    static String f1(double v) {
        return EstimateFormatter.f1(v);
    }
}

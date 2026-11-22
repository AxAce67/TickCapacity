package sh.aki.tickcapacity.report;

import sh.aki.tickcapacity.bench.BenchmarkResult;
import sh.aki.tickcapacity.capacity.CapacityService;
import sh.aki.tickcapacity.core.analysis.Finding;
import sh.aki.tickcapacity.core.analysis.HourlyProfile;
import sh.aki.tickcapacity.core.estimate.CapacityEstimate;
import sh.aki.tickcapacity.core.estimate.Confidence;
import sh.aki.tickcapacity.core.estimate.StageResult;
import sh.aki.tickcapacity.core.estimate.Thresholds;
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
import java.util.Optional;

/** Writes the Markdown and HTML reports. Blocking; the data is captured on the main thread beforehand. */
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
            Thresholds thresholds
    ) {
    }

    public record Written(Path markdown, Path html) {
    }

    private static final DateTimeFormatter FILE_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmmss");
    static final DateTimeFormatter SHOWN_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private ReportWriter() {
    }

    public static Written write(Path dir, ReportData d, Messages m) throws IOException {
        Files.createDirectories(dir);
        LocalDateTime now = LocalDateTime.now();
        String base = "report-" + now.format(FILE_TS);
        Path md = dir.resolve(base + ".md");
        Path html = dir.resolve(base + ".html");
        Files.writeString(md, markdown(d, m, now), StandardCharsets.UTF_8);
        Files.writeString(html, HtmlReport.render(d, m, now), StandardCharsets.UTF_8);
        return new Written(md, html);
    }

    /** The estimate the headline is about, if there is one worth stating. */
    static Optional<CapacityEstimate> headlineEstimate(CapacityService.Snapshot s) {
        return s.best().filter(e -> Double.isFinite(e.recommended().point()));
    }

    /** Low-confidence values read as approximate ("約43" / "~43"). */
    static String players(Messages m, CapacityEstimate e, double v) {
        String n = EstimateFormatter.players(v);
        return e.confidence() == Confidence.LOW ? m.plain("report.approx", n) : n;
    }

    static String headline(Messages m, CapacityService.Snapshot s) {
        return headlineEstimate(s)
                .map(e -> m.plain("report.headline", EstimateFormatter.players(e.recommended().point())))
                .orElseGet(() -> m.plain("report.headline.unknown"));
    }

    static String markdown(ReportData d, Messages m, LocalDateTime now) {
        CapacityService.Snapshot s = d.snapshot();
        HardwareInfo h = d.hardware();
        StringBuilder sb = new StringBuilder(8192);
        sb.append("# ").append(headline(m, s));
        headlineEstimate(s).filter(e -> e.confidence() == Confidence.LOW)
                .ifPresent(e -> sb.append(" (").append(m.plain("report.rough")).append(")"));
        sb.append("\n\n");
        sb.append(m.plain("report.meta", now.format(SHOWN_TS), d.pluginVersion(), h.minecraftVersion(),
                h.cpuModel())).append("\n\n");

        sb.append("## ").append(m.plain("report.section.capacity")).append("\n\n");
        bullets(sb, EstimateFormatter.plainLines(m, s.observed()));
        if (s.benchmarkEstimate() != null) {
            sb.append('\n');
            bullets(sb, EstimateFormatter.plainLines(m, s.benchmarkEstimate()));
            sb.append("- ").append(m.plain("report.benchmark-id", s.benchmark().id(),
                    m.plain("ui.status." + s.benchmark().status().name()))).append('\n');
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
            table(sb, List.of(m.plain("report.col.hour"), m.plain("report.col.players-avg-max"),
                    m.plain("report.col.mspt-median"), "p95", m.plain("report.col.samples")));
            for (HourlyProfile.Hour hr : hourly.hours()) {
                if (hr.samples() > 0) {
                    row(sb, List.of(hr.hour() + ":00", f1(hr.avgPlayers()) + " / " + hr.maxPlayers(),
                            f1(hr.medianMspt()), f1(hr.medianP95()), Integer.toString(hr.samples())));
                }
            }
            sb.append('\n');
        }

        sb.append("## ").append(m.plain("report.section.bottlenecks")).append("\n\n");
        for (Finding f : d.findings()) {
            sb.append("- **").append(m.plain("severity." + f.severity().name())).append("** ")
                    .append(m.plain("finding." + f.key(), f.args().toArray()));
            if (m.has("advice." + f.key())) {
                sb.append("\n  - ").append(m.plain("advice." + f.key(), f.args().toArray()));
            }
            sb.append('\n');
        }
        if (d.sparkPresent()) {
            sb.append("- ").append(m.plain("analyze.spark-hint")).append('\n');
        }
        sb.append('\n');

        sb.append("## ").append(m.plain("report.section.current")).append("\n\n");
        TickSummary t = d.last60s();
        table(sb, List.of("TPS", m.plain("report.col.mspt-mean"), "p50", "p95", "p99", m.plain("report.col.max"),
                m.plain("report.players")));
        row(sb, List.of(f1(d.tps60s()), f1(t.meanMs()), f1(t.p50Ms()), f1(t.p95Ms()), f1(t.p99Ms()), f1(t.maxMs()),
                Integer.toString(d.playersOnline())));
        sb.append('\n');

        BenchmarkResult b = s.benchmark();
        if (b != null && !b.stages().isEmpty()) {
            sb.append("## ").append(m.plain("report.section.stages", b.id())).append("\n\n");
            table(sb, stageHeaders(m));
            for (StageResult st : b.stages()) {
                row(sb, stageRow(st));
            }
            sb.append('\n').append(benchParams(m, b)).append("\n\n");
        }

        sb.append("## ").append(m.plain("report.section.hardware")).append("\n\n");
        table(sb, List.of(m.plain("report.col.item"), m.plain("report.col.value")));
        for (String[] r : hardwareRows(m, h)) {
            row(sb, List.of(r[0], r[1]));
        }
        sb.append('\n');

        sb.append("## ").append(m.plain("report.section.method")).append("\n\n");
        sb.append(m.plain("report.method")).append('\n');
        return sb.toString();
    }

    static List<String> stageHeaders(Messages m) {
        return List.of(m.plain("report.col.run"), m.plain("report.virtual-players"), m.plain("report.col.mspt-mean"),
                "p95", "p99", m.plain("report.col.max"), "TPS", "CPU", m.plain("report.col.gc"),
                m.plain("report.col.chunks"), m.plain("report.col.new-chunks"), m.plain("report.col.entities"));
    }

    static List<String> stageRow(StageResult st) {
        TickSummary ts = st.ticks();
        return List.of(Integer.toString(st.run() + 1), Integer.toString(st.virtualPlayers()), f1(ts.meanMs()),
                f1(ts.p95Ms()), f1(ts.p99Ms()), f1(ts.maxMs()), f1(ts.impliedTps()), pct(st.processCpu()),
                Long.toString(st.gcTimeMs()), Integer.toString(st.loadedChunks()), Integer.toString(st.newChunks()),
                Integer.toString(st.entities()));
    }

    static String benchParams(Messages m, BenchmarkResult b) {
        BenchmarkResult.Parameters p = b.parameters();
        String step = p.playersPerStage() > 0 ? Integer.toString(p.playersPerStage()) : m.plain("report.auto");
        return m.plain("report.bench-params", p.chunkRadius(), step, p.settleSeconds(), p.stageSeconds(), p.seed(),
                p.entitiesPerPlayer().values().stream().mapToInt(Integer::intValue).sum(),
                Math.max(1, p.repetitions()), f1(p.movementBlocksPerSecond()));
    }

    static List<String[]> hardwareRows(Messages m, HardwareInfo h) {
        return List.of(
                new String[]{"CPU", m.plain("report.hw.cpu", h.cpuModel(), h.availableProcessors())},
                new String[]{m.plain("report.hw.ram"), h.physicalMemoryMb() < 0 ? "?" : h.physicalMemoryMb() + " MB"},
                new String[]{m.plain("report.hw.heap"), h.maxHeapMb() + " MB"},
                new String[]{"OS", h.osName() + " " + h.osVersion() + " (" + h.osArch() + ")"},
                new String[]{"Java", h.javaVersion() + " (" + h.vmName() + ", " + h.javaVendor() + ")"},
                new String[]{"GC", String.join(", ", h.garbageCollectors())},
                new String[]{m.plain("report.hw.flags"), h.jvmFlags().isEmpty() ? "-" : String.join(" ", h.jvmFlags())},
                new String[]{m.plain("report.hw.server"), h.serverVersion()},
                new String[]{"view / simulation distance", h.viewDistance() + " / " + h.simulationDistance()},
                new String[]{m.plain("report.hw.disk"), h.diskFreeMb() + " MB"});
    }

    private static void bullets(StringBuilder sb, List<String> lines) {
        lines.forEach(l -> sb.append("- ").append(l).append('\n'));
    }

    private static void table(StringBuilder sb, List<String> headers) {
        row(sb, headers);
        sb.append("|").append("---|".repeat(headers.size())).append('\n');
    }

    private static void row(StringBuilder sb, List<String> cells) {
        sb.append("| ");
        for (String c : cells) {
            sb.append(c.replace("|", "\\|")).append(" | ");
        }
        sb.setLength(sb.length() - 1);
        sb.append('\n');
    }

    static String pct(double v) {
        return Double.isNaN(v) ? "?" : String.format(Locale.ROOT, "%.0f%%", v * 100);
    }

    static String f1(double v) {
        return EstimateFormatter.f1(v);
    }
}

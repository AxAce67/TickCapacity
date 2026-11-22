package sh.aki.tickcapacity.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import sh.aki.tickcapacity.TickCapacityPlugin;
import sh.aki.tickcapacity.bench.BenchmarkResult;
import sh.aki.tickcapacity.bench.BenchmarkRunner;
import sh.aki.tickcapacity.capacity.CapacityService;
import sh.aki.tickcapacity.compat.ServerCompat;
import sh.aki.tickcapacity.core.analysis.BottleneckAnalyzer;
import sh.aki.tickcapacity.core.analysis.Finding;
import sh.aki.tickcapacity.core.analysis.HourlyProfile;
import sh.aki.tickcapacity.core.analysis.NextStepAdvisor;
import sh.aki.tickcapacity.core.estimate.ActivityAnalyzer;
import sh.aki.tickcapacity.core.estimate.CapacityEstimate;
import sh.aki.tickcapacity.core.estimate.Confidence;
import sh.aki.tickcapacity.core.estimate.Thresholds;
import sh.aki.tickcapacity.core.model.Sample;
import sh.aki.tickcapacity.core.model.TickSummary;
import sh.aki.tickcapacity.i18n.Messages;
import sh.aki.tickcapacity.monitor.HardwareInfo;
import sh.aki.tickcapacity.monitor.JvmMetrics;
import sh.aki.tickcapacity.monitor.TickMonitor;
import sh.aki.tickcapacity.monitor.WorldCounts;
import sh.aki.tickcapacity.report.EstimateFormatter;
import sh.aki.tickcapacity.report.InsightFormatter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Chat screens. Chat is about 25 Japanese or 50 Latin characters wide, so each line holds one fact and the
 * details go into hover text. Hover text is built with Adventure rather than MiniMessage so values cannot
 * break it.
 */
final class Views {

    private static final int COMPACT_NOTES = 2;

    private final TickCapacityPlugin plugin;

    Views(TickCapacityPlugin plugin) {
        this.plugin = plugin;
    }

    private Messages m() {
        return plugin.messages();
    }

    private Thresholds th() {
        return plugin.settings().thresholds();
    }

    private static Component hover(Component visible, String text) {
        return visible.hoverEvent(HoverEvent.showText(Component.text(text, NamedTextColor.GRAY)));
    }

    /** The ".short" variant of a message if there is one. */
    private String shortText(String key, Object... args) {
        Messages m = m();
        return m.has(key + ".short") ? m.plain(key + ".short", args) : m.plain(key, args);
    }

    private void title(CommandSender sender, String text) {
        sender.sendMessage(m().get("ui.title", text));
    }

    void dashboard(CommandSender sender) {
        Messages m = m();
        TickMonitor ticks = plugin.tickMonitor();
        TickSummary recent = ticks.recent(10);
        TickSummary minute = ticks.recent(60);
        double mspt = recent.isEmpty() ? 0 : recent.meanMs();
        int online = plugin.getServer().getOnlinePlayers().size();

        title(sender, "TickCapacity");
        sender.sendMessage(hover(m.get("ui.dash.state." + state(mspt), f1(ticks.tps(5)), load(mspt)),
                m.plain("ui.hover.state", f1(minute.meanMs()), f1(minute.p95Ms()), f0(th().limitMspt()))));

        CapacityService.Snapshot snap = plugin.capacity().snapshot();
        Optional<CapacityEstimate> best = snap == null ? Optional.empty() : snap.best();
        if (snap == null) {
            sender.sendMessage(m.get("ui.dash.players.computing", online));
        } else if (best.isPresent() && Double.isFinite(best.get().recommended().point())) {
            CapacityEstimate e = best.get();
            String approx = e.confidence() == Confidence.LOW ? "~" : "";
            sender.sendMessage(hover(m.get("ui.dash.players", online, approx + players(e.recommended().point()),
                            approx + players(e.limit().point()), m.plain("confidence." + e.confidence().name())),
                    m.plain("ui.hover.players", m.plain("ui.source." + e.source().name()),
                            EstimateFormatter.range(e.recommended()), EstimateFormatter.range(e.limit()))));
        } else {
            sender.sendMessage(hover(m.get("ui.dash.players.unknown", online), m.plain("ui.hover.players-unknown")));
        }

        List<Finding> findings = findings();
        Finding worst = findings.stream().filter(f -> f.severity() != Finding.Severity.INFO).findFirst().orElse(null);
        if (worst == null) {
            sender.sendMessage(m.get("ui.dash.issue.none"));
        } else {
            sender.sendMessage(hover(m.get("ui.dash.issue", shortText("finding." + worst.key(), worst.args().toArray()),
                    color(worst.severity())), findingHover(worst)));
        }

        if (snap != null) {
            NextStepAdvisor.Step step = NextStepAdvisor.advise(new NextStepAdvisor.Input(snap.observed(),
                    snap.benchmark() != null, plugin.benchmark().isRunning(), plugin.benchmarkEarActive(), online,
                    trusted(snap).map(e -> e.recommended().point()).orElse(Double.NaN),
                    trusted(snap).map(e -> e.limit().point()).orElse(Double.NaN),
                    worst == null ? null : worst.severity(), sender.hasPermission(TickCapacityCommand.PERM_ADMIN)));
            Component line = hover(m.get(step.command() == null ? "ui.dash.next.plain" : "ui.dash.next",
                            shortText(step.key(), step.args().toArray()), step.command() == null ? "" : step.command()),
                    m.plain(step.key(), step.args().toArray()));
            sender.sendMessage(line);
        }
        BenchmarkRunner bench = plugin.benchmark();
        if (bench.isRunning()) {
            sender.sendMessage(benchStatus(bench));
        }
        if (ServerCompat.isPaused(plugin.getServer())) {
            sender.sendMessage(m.get("ui.dash.paused"));
        }
        sender.sendMessage(m.get("ui.dash.buttons"));
        if (sender.hasPermission(TickCapacityCommand.PERM_ADMIN)) {
            sender.sendMessage(m.get("ui.dash.buttons.admin"));
        }
    }

    private static Optional<CapacityEstimate> trusted(CapacityService.Snapshot s) {
        return s.best().filter(e -> e.confidence().compareTo(Confidence.MEDIUM) >= 0);
    }

    void capacity(CommandSender sender, CapacityService.Snapshot s, boolean details) {
        Messages m = m();
        title(sender, m.plain("ui.cap.title"));
        if (details) {
            EstimateFormatter.lines(m, s.observed()).forEach(sender::sendMessage);
            if (s.benchmarkEstimate() != null) {
                sender.sendMessage(Component.empty());
                EstimateFormatter.lines(m, s.benchmarkEstimate()).forEach(sender::sendMessage);
                sender.sendMessage(m.get("ui.cap.bench-from", s.benchmark().id()));
            }
            if (s.calibration() != null) {
                sender.sendMessage(InsightFormatter.calibration(m, s.calibration()));
            }
            sender.sendMessage(Component.empty());
            InsightFormatter.activity(m, s.activity()).forEach(sender::sendMessage);
            sender.sendMessage(Component.empty());
            InsightFormatter.hourly(m, s.hourly()).forEach(sender::sendMessage);
            sender.sendMessage(m.get("ui.cap.buttons.details"));
            return;
        }
        String days = noteValueOf(s.observed(), "short-history").map(Views::f1).orElse("3+");
        sender.sendMessage(m.get("ui.cap.observed", days, s.observed().samples()));
        compactEstimate(sender, s.observed());
        if (s.benchmarkEstimate() != null) {
            sender.sendMessage(hover(m.get("ui.cap.bench", s.benchmarkEstimate().isCalibrated()
                    ? m.plain("ui.cap.calibrated") : ""), s.benchmark().id()));
            compactEstimate(sender, s.benchmarkEstimate());
        }
        ActivityAnalyzer.Result a = s.activity();
        if (a.available()) {
            sender.sendMessage(hover(m.get("ui.cap.activity", players(a.scenarios().get(0).recommended()),
                            players(a.scenarios().get(1).recommended()), players(a.scenarios().get(2).recommended())),
                    String.join("\n", InsightFormatter.activityPlain(m, a))));
        }
        HourlyProfile h = s.hourly();
        h.peakPlayers().ifPresent(peak -> sender.sendMessage(hover(m.get("ui.cap.hours", hourSpark(h), peak.hour(),
                        h.quietest().map(q -> Integer.toString(q.hour())).orElse("-")),
                m.plain("ui.hover.hours", peak.hour(), f1(peak.avgPlayers()), peak.maxPlayers()))));
        sender.sendMessage(m.get("ui.cap.buttons"));
    }

    private void compactEstimate(CommandSender sender, CapacityEstimate e) {
        Messages m = m();
        String conf = m.plain("confidence." + e.confidence().name());
        if (e.confidence() == Confidence.INSUFFICIENT) {
            sender.sendMessage(m.get("ui.cap.insufficient"));
        } else if (!Double.isFinite(e.recommended().point())) {
            sender.sendMessage(m.get("ui.cap.at-least", players(e.recommended().low())));
        } else {
            sender.sendMessage(hover(m.get("ui.cap.result", players(e.recommended().point()),
                            players(e.limit().point()), conf),
                    m.plain("ui.hover.ranges", EstimateFormatter.range(e.recommended()),
                            EstimateFormatter.range(e.limit()),
                            e.provenComfortablePlayers() > 0 ? e.provenComfortablePlayers() : "-")));
        }
        List<CapacityEstimate.Note> notes = e.notesOrEmpty().stream()
                .filter(n -> m.has("note." + n.key()) && !n.key().equals("synthetic-load")
                        && !n.key().equals("repeated-runs") && !n.key().equals("calibrated"))
                .toList();
        for (int i = 0; i < Math.min(COMPACT_NOTES, notes.size()); i++) {
            CapacityEstimate.Note n = notes.get(i);
            String v = noteValue(n);
            sender.sendMessage(hover(m.get("ui.note", shortText("note." + n.key(), v)),
                    m.plain("note." + n.key(), v).replaceFirst("^•\\s*", "")));
        }
        if (notes.size() > COMPACT_NOTES) {
            sender.sendMessage(m.get("ui.cap.more-notes", notes.size() - COMPACT_NOTES));
        }
    }

    private static Optional<Double> noteValueOf(CapacityEstimate e, String key) {
        return e.notesOrEmpty().stream().filter(n -> n.key().equals(key)).findFirst().map(CapacityEstimate.Note::value);
    }

    private static String noteValue(CapacityEstimate.Note n) {
        double v = n.value();
        if (Double.isNaN(v)) {
            return "";
        }
        return switch (n.key()) {
            case "spawns-blocked" -> String.format(Locale.ROOT, "%.0f%%", v * 100);
            case "too-few-samples", "too-few-player-counts", "too-few-stages", "memory-limited" -> f0(v);
            case "short-history", "moving-players", "extrapolated" -> f1(v);
            default -> String.format(Locale.ROOT, "%.2f", v);
        };
    }

    void performance(CommandSender sender) {
        Messages m = m();
        TickMonitor t = plugin.tickMonitor();
        JvmMetrics jvm = plugin.jvm();
        TickSummary s60 = t.recent(60);
        WorldCounts counts = WorldCounts.of(plugin.getServer());
        title(sender, m.plain("ui.perf.title"));
        sender.sendMessage(m.get("ui.perf.tps", tpsColor(t.tps(5)), f1(t.tps(5)), f1(t.tps(60))));
        sender.sendMessage(hover(m.get("ui.perf.mspt", stateColor(s60.meanMs()), f1(s60.meanMs()), f1(s60.p95Ms()),
                f0(s60.maxMs())), m.plain("ui.hover.mspt", f0(th().limitMspt()))));
        long used = jvm.heapUsedMb();
        long max = jvm.heapMaxMb();
        long gcMs = plugin.sampler().recent(1).stream().mapToLong(Sample::gcTimeMs).sum();
        sender.sendMessage(hover(m.get("ui.perf.resources", pct(jvm.processCpu()), max > 0 ? used * 100 / max : 0),
                m.plain("ui.hover.resources", pct(jvm.processCpu()), pct(jvm.systemCpu()),
                        Runtime.getRuntime().availableProcessors(), used, max, gcMs)));
        sender.sendMessage(hover(m.get("ui.perf.world", counts.loadedChunks(), counts.entities(),
                counts.tickingBlockEntities()), m.plain("ui.hover.world")));

        sender.sendMessage(m.get("ui.perf.causes", plugin.sampler().recent(10).size()));
        List<Finding> findings = findings();
        List<Finding> shown = findings.stream().filter(f -> f.severity() != Finding.Severity.INFO).toList();
        if (shown.isEmpty()) {
            sender.sendMessage(m.get("ui.perf.no-issues"));
        }
        for (Finding f : shown) {
            sender.sendMessage(hover(m.get("ui.perf.issue", shortText("finding." + f.key(), f.args().toArray()),
                    color(f.severity())), findingHover(f)));
        }
        List<Finding> tips = findings.stream()
                .filter(f -> f.severity() == Finding.Severity.INFO && f.category() != Finding.Category.HEALTHY).toList();
        for (Finding f : tips) {
            sender.sendMessage(hover(m.get("ui.perf.tip", shortText("finding." + f.key(), f.args().toArray())),
                    findingHover(f)));
        }
        HardwareInfo h = plugin.hardware();
        sender.sendMessage(hover(m.get("ui.perf.env", shortCpu(h.cpuModel()), h.availableProcessors(),
                f1(h.maxHeapMb() / 1024.0)), hardwareHover(h)));
        boolean mainThreadIssue = shown.stream().anyMatch(f -> f.category() == Finding.Category.MAIN_THREAD
                || f.category() == Finding.Category.SPIKES || f.category() == Finding.Category.ENTITIES
                || f.category() == Finding.Category.BLOCK_ENTITIES);
        boolean spark = plugin.sparkPresent();
        boolean admin = sender.hasPermission(TickCapacityCommand.PERM_ADMIN);
        if (!spark && mainThreadIssue) {
            sender.sendMessage(hover(m.get("ui.perf.spark-missing"), m.plain("analyze.spark-missing")));
        }
        sender.sendMessage(spark && admin ? m.get("ui.perf.buttons.spark") : m.get("ui.perf.buttons"));
    }

    private String findingHover(Finding f) {
        Messages m = m();
        String text = m.plain("finding." + f.key(), f.args().toArray());
        String advice = "advice." + f.key();
        return m.has(advice) ? text + "\n\n→ " + m.plain(advice, f.args().toArray()) : text;
    }

    private static String shortCpu(String cpu) {
        String s = cpu.replaceAll("\\((R|TM)\\)", "").replaceAll("\\s+CPU\\s+@.*$", "").replaceAll("\\s+", " ").trim();
        return s.length() > 22 ? s.substring(0, 21) + "…" : s;
    }

    private String hardwareHover(HardwareInfo h) {
        return h.cpuModel() + " (" + h.availableProcessors() + " threads)\n"
                + "RAM " + (h.physicalMemoryMb() < 0 ? "?" : h.physicalMemoryMb() + " MB") + ", heap " + h.maxHeapMb()
                + " MB\n" + h.osName() + " " + h.osVersion() + " (" + h.osArch() + ")\n"
                + "Java " + h.javaVersion() + " " + h.vmName() + "\nGC " + String.join(", ", h.garbageCollectors())
                + "\n" + h.serverVersion() + "\nview " + h.viewDistance() + " / simulation " + h.simulationDistance()
                + "\nDisk free " + h.diskFreeMb() + " MB";
    }

    void benchOverview(CommandSender sender, BenchmarkResult last) {
        Messages m = m();
        BenchmarkRunner bench = plugin.benchmark();
        boolean admin = sender.hasPermission(TickCapacityCommand.PERM_ADMIN);
        title(sender, m.plain("ui.bench.title"));
        if (bench.isRunning()) {
            sender.sendMessage(benchStatus(bench));
            if (admin) {
                sender.sendMessage(m.get("ui.bench.stop-button"));
            }
            return;
        }
        sender.sendMessage(hover(m.get("ui.bench.explain"), m.plain("ui.hover.bench")));
        if (last != null) {
            CapacityEstimate e = last.bestEstimate();
            sender.sendMessage(hover(m.get("ui.bench.last", players(e.recommended().point()),
                            players(e.limit().point()), m.plain("confidence." + e.confidence().name())),
                    m.plain("ui.hover.bench-last", last.id(), EstimateFormatter.range(e.recommended()),
                            EstimateFormatter.range(e.limit()))));
        }
        if (plugin.benchmarkEarActive()) {
            sender.sendMessage(hover(m.get("ui.bench.ear"),
                    m.plain("ui.hover.ear", plugin.settings().benchmark().worldName())));
        }
        sender.sendMessage(admin ? m.get("ui.bench.buttons.admin") : m.get("ui.bench.buttons"));
    }

    Component benchStatus(BenchmarkRunner bench) {
        Messages m = m();
        Component line = bench.phase() == BenchmarkRunner.Phase.PREPARING
                ? m.get("ui.bench.status.preparing", bench.pregenPercent())
                : m.get("ui.bench.status.running", m.plain("bench.phase." + bench.phase().name()),
                bench.activeVirtualPlayers());
        return bench.repetitions() > 1
                ? line.append(m.get("ui.bench.status.run", bench.currentRun() + 1, bench.repetitions())) : line;
    }

    /** "2026-11-22_104053" to "11/22 10:40" */
    static String shortId(String id) {
        if (id == null || id.length() < 15) {
            return String.valueOf(id);
        }
        return id.substring(5, 7) + "/" + id.substring(8, 10) + " " + id.substring(11, 13) + ":" + id.substring(13, 15);
    }

    void help(CommandSender sender) {
        Messages m = m();
        title(sender, "TickCapacity " + plugin.getPluginMeta().getVersion());
        List<String> keys = new ArrayList<>(List.of("ui.help.dashboard", "ui.help.capacity", "ui.help.perf",
                "ui.help.monitor", "ui.help.bench"));
        if (sender.hasPermission(TickCapacityCommand.PERM_ADMIN)) {
            keys.add("ui.help.report");
            keys.add("ui.help.admin");
        }
        for (String key : keys) {
            sender.sendMessage(hover(m.get(key), m.plain(key + ".hover")));
        }
    }

    void adminHelp(CommandSender sender) {
        Messages m = m();
        title(sender, m.plain("ui.admin.title"));
        for (String key : List.of("ui.admin.reload", "ui.admin.alerts", "ui.admin.alert-test",
                "ui.admin.calibration-reset", "ui.admin.profile")) {
            sender.sendMessage(hover(m.get(key), m.plain(key + ".hover")));
        }
    }

    List<Finding> findings() {
        Thresholds s = th();
        HardwareInfo h = plugin.hardware();
        BottleneckAnalyzer.Context ctx = new BottleneckAnalyzer.Context(h.availableProcessors(), h.maxHeapMb(),
                h.viewDistance(), h.simulationDistance(), s.comfortableMspt(), s.limitMspt());
        return new BottleneckAnalyzer().analyze(plugin.sampler().recent(10), ctx);
    }

    private String state(double mspt) {
        if (mspt < th().comfortableMspt()) {
            return "ok";
        }
        return mspt < th().limitMspt() ? "busy" : "heavy";
    }

    private String stateColor(double mspt) {
        return switch (state(Double.isNaN(mspt) ? 0 : mspt)) {
            case "ok" -> "green";
            case "busy" -> "yellow";
            default -> "red";
        };
    }

    /** 12 ms is 24% of the 50 ms budget. */
    private String load(double mspt) {
        return f0(Math.max(0, mspt) / th().limitMspt() * 100);
    }

    private static String hourSpark(HourlyProfile p) {
        char[] spark = {'▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};
        double max = p.hours().stream().filter(h -> h.samples() > 0).mapToDouble(HourlyProfile.Hour::avgPlayers)
                .max().orElse(0);
        StringBuilder sb = new StringBuilder();
        for (HourlyProfile.Hour h : p.hours()) {
            if (h.samples() == 0) {
                sb.append(' ');
            } else {
                int i = max > 0 ? (int) Math.round(h.avgPlayers() / max * 7) : 0;
                sb.append(spark[Math.clamp(i, 0, 7)]);
            }
        }
        return sb.toString();
    }

    static String color(Finding.Severity s) {
        return switch (s) {
            case CRITICAL -> "red";
            case WARNING -> "yellow";
            case INFO -> "aqua";
        };
    }

    private static String tpsColor(double tps) {
        if (Double.isNaN(tps) || tps >= 19.5) {
            return "green";
        }
        return tps >= 17 ? "yellow" : "red";
    }

    static String players(double v) {
        return EstimateFormatter.players(v);
    }

    static String f0(double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.0f", v);
    }

    static String f1(double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.1f", v);
    }

    static String pct(double v) {
        return Double.isNaN(v) ? "?" : String.format(Locale.ROOT, "%.0f%%", v * 100);
    }
}

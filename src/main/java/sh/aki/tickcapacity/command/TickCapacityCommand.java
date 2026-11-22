package sh.aki.tickcapacity.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import sh.aki.tickcapacity.TickCapacityPlugin;
import sh.aki.tickcapacity.bench.BenchmarkResult;
import sh.aki.tickcapacity.bench.BenchmarkRunner;
import sh.aki.tickcapacity.bench.ResultRepository;
import sh.aki.tickcapacity.capacity.CapacityService;
import sh.aki.tickcapacity.core.analysis.Finding;
import sh.aki.tickcapacity.core.model.TickSummary;
import sh.aki.tickcapacity.display.DisplayService.Mode;
import sh.aki.tickcapacity.i18n.Messages;
import sh.aki.tickcapacity.monitor.HardwareInfo;
import sh.aki.tickcapacity.monitor.TickMonitor;
import sh.aki.tickcapacity.report.EstimateFormatter;
import sh.aki.tickcapacity.report.ReportWriter;
import sh.aki.tickcapacity.report.ResultComparison;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.stream.Collectors;

/** /tickcapacity, alias /tc. Anything that touches files runs on the I/O thread. */
public final class TickCapacityCommand implements BasicCommand {

    static final String PERM_USE = "tickcapacity.use";
    static final String PERM_ADMIN = "tickcapacity.admin";

    private static final List<String> SUBCOMMANDS = List.of("capacity", "perf", "monitor", "bench", "report", "admin",
            "help");
    private static final Set<String> ADMIN_SUBCOMMANDS = Set.of("report", "admin");

    private final TickCapacityPlugin plugin;
    private final Views views;

    public TickCapacityCommand(TickCapacityPlugin plugin) {
        this.plugin = plugin;
        this.views = new Views(plugin);
    }

    /**
     * No @Override: the 1.20.6 API lacks this method, but on 1.21+ it still overrides the default and hides the
     * command from players without permission. execute() checks the permission anyway.
     */
    public String permission() {
        return PERM_USE;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        Messages m = plugin.messages();
        if (!sender.hasPermission(PERM_USE)) {
            sender.sendMessage(m.get("error.no-permission"));
            return;
        }
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        String[] rest = args.length == 0 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        if (ADMIN_SUBCOMMANDS.contains(sub) && !sender.hasPermission(PERM_ADMIN)) {
            sender.sendMessage(m.get("error.no-permission"));
            return;
        }
        switch (sub) {
            case "" -> views.dashboard(sender);
            case "capacity" -> capacity(sender, rest);
            case "perf" -> views.performance(sender);
            case "monitor" -> monitor(sender, rest);
            case "bench" -> bench(sender, rest);
            case "report" -> report(sender);
            case "admin" -> admin(sender, rest);
            case "help" -> views.help(sender);
            default -> {
                sender.sendMessage(m.get("error.unknown-subcommand", sub));
                views.help(sender);
            }
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        if (!sender.hasPermission(PERM_USE)) {
            return List.of();
        }
        boolean admin = sender.hasPermission(PERM_ADMIN);
        if (args.length <= 1) {
            String prefix = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream().filter(s -> admin || !ADMIN_SUBCOMMANDS.contains(s))
                    .filter(s -> s.startsWith(prefix)).toList();
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = switch (sub) {
            case "capacity" -> args.length == 2 ? List.of("details") : List.of();
            case "monitor" -> args.length == 2 ? List.of("bossbar", "actionbar", "tab", "off") : List.of();
            case "bench" -> {
                if (args.length == 2) {
                    yield admin ? List.of("start", "stop", "list", "compare") : List.of("list", "compare");
                }
                if (args[1].equalsIgnoreCase("start") && admin) {
                    yield List.of("1", "3", "5", "force");
                }
                if (args[1].equalsIgnoreCase("compare") && args.length <= 4) {
                    yield plugin.results().listIds().stream().limit(20).toList();
                }
                yield List.of();
            }
            case "admin" -> args.length == 2 && admin
                    ? List.of("reload", "alerts", "alert-test", "calibration-reset", "profile") : List.of();
            default -> List.of();
        };
        return options.stream().filter(s -> s.startsWith(last)).toList();
    }

    private void withFreshSnapshot(Consumer<CapacityService.Snapshot> then) {
        var settings = plugin.settings();
        HardwareInfo hw = plugin.hardware();
        plugin.io().execute(() -> {
            CapacityService.Snapshot s = plugin.capacity().compute(settings.thresholds(), settings.historyDays(), hw,
                    settings.useCalibration());
            plugin.sync(() -> then.accept(s));
        });
    }

    private void capacity(CommandSender sender, String[] args) {
        boolean details = args.length > 0 && args[0].toLowerCase(Locale.ROOT).startsWith("detail");
        sender.sendMessage(plugin.messages().get("ui.computing"));
        withFreshSnapshot(s -> views.capacity(sender, s, details));
    }

    private void monitor(CommandSender sender, String[] args) {
        Messages m = plugin.messages();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(m.get("error.players-only"));
            return;
        }
        var service = plugin.monitorService();
        Set<Mode> current = service.modes(player);
        Set<Mode> next;
        if (args.length == 0) {
            next = current.isEmpty() ? plugin.settings().monitor().defaultModes() : Set.of();
        } else if (args[0].equalsIgnoreCase("off")) {
            next = Set.of();
        } else {
            Optional<Mode> mode = Mode.parse(args[0]);
            if (mode.isEmpty()) {
                sender.sendMessage(m.get("monitor.usage"));
                return;
            }
            next = current.isEmpty() ? EnumSet.noneOf(Mode.class) : EnumSet.copyOf(current);
            if (!next.remove(mode.get())) {
                next.add(mode.get());
            }
        }
        service.setModes(player, next);
        sender.sendMessage(next.isEmpty() ? m.get("monitor.off")
                : m.get("monitor.on", next.stream().map(x -> m.plain("display." + x.name()))
                .collect(Collectors.joining(", "))));
        sender.sendMessage(m.get("monitor.switch-buttons"));
    }

    private void bench(CommandSender sender, String[] args) {
        Messages m = plugin.messages();
        BenchmarkRunner bench = plugin.benchmark();
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission(PERM_ADMIN);
        if ((action.equals("start") || action.equals("stop")) && !admin) {
            sender.sendMessage(m.get("error.no-permission"));
            return;
        }
        switch (action) {
            case "start" -> benchStart(sender, Arrays.copyOfRange(args, 1, args.length));
            case "stop" -> {
                if (!bench.isRunning()) {
                    sender.sendMessage(m.get("bench.not-running"));
                } else {
                    bench.cancel("bench.reason.cancelled");
                }
            }
            case "list" -> benchList(sender);
            case "compare" -> benchCompare(sender, Arrays.copyOfRange(args, 1, args.length));
            default -> plugin.io().execute(() -> {
                BenchmarkResult last = plugin.results().latestUsable().orElse(null);
                plugin.sync(() -> views.benchOverview(sender, last));
            });
        }
    }

    private void benchStart(CommandSender sender, String[] args) {
        Messages m = plugin.messages();
        boolean force = false;
        int runs = 1;
        for (String a : args) {
            if (a.equalsIgnoreCase("force")) {
                force = true;
            } else {
                try {
                    runs = Integer.parseInt(a);
                } catch (NumberFormatException e) {
                    sender.sendMessage(m.get("bench.usage"));
                    return;
                }
            }
        }
        var c = plugin.settings().benchmark();
        String error = plugin.benchmark().start(sender, force, runs);
        if (error != null) {
            sender.sendMessage(m.get(error, c.worldName(), c.maxRepetitions()));
        } else {
            sender.sendMessage(m.get("bench.started", c.maxVirtualPlayers(), runs));
        }
    }

    private void benchList(CommandSender sender) {
        Messages m = plugin.messages();
        plugin.io().execute(() -> {
            List<BenchmarkResult> list = plugin.results().listIds().stream().limit(10)
                    .map(plugin.results()::load).flatMap(Optional::stream).toList();
            plugin.sync(() -> {
                if (list.isEmpty()) {
                    sender.sendMessage(m.get("results.none"));
                    return;
                }
                sender.sendMessage(m.get("results.header"));
                for (BenchmarkResult r : list) {
                    var e = r.bestEstimate();
                    sender.sendMessage(m.get("results.row", r.id(), Views.shortId(r.id()),
                            EstimateFormatter.players(e.recommended().point()),
                            EstimateFormatter.players(e.limit().point()),
                            m.plain("confidence." + e.confidence().name()), m.plain("ui.status." + r.status().name())));
                }
                sender.sendMessage(m.get("results.hint"));
            });
        });
    }

    private void benchCompare(CommandSender sender, String[] args) {
        Messages m = plugin.messages();
        if (args.length < 2) {
            sender.sendMessage(m.get("compare.usage"));
            return;
        }
        String a = args[0];
        String b = args[1];
        if (!ResultRepository.isValidId(a) || !ResultRepository.isValidId(b)) {
            sender.sendMessage(m.get("compare.not-found"));
            return;
        }
        plugin.io().execute(() -> {
            Optional<BenchmarkResult> ra = plugin.results().load(a);
            Optional<BenchmarkResult> rb = plugin.results().load(b);
            plugin.sync(() -> {
                if (ra.isEmpty() || rb.isEmpty()) {
                    sender.sendMessage(m.get("compare.not-found"));
                    return;
                }
                ResultComparison.compare(m, ra.get(), rb.get()).forEach(sender::sendMessage);
            });
        });
    }

    private void report(CommandSender sender) {
        Messages m = plugin.messages();
        sender.sendMessage(m.get("report.writing"));
        TickMonitor t = plugin.tickMonitor();
        HardwareInfo hw = plugin.hardware();
        TickSummary last60 = t.recent(60);
        double tps = t.tps(60);
        int online = plugin.getServer().getOnlinePlayers().size();
        List<Finding> findings = views.findings();
        boolean spark = plugin.sparkPresent();
        var settings = plugin.settings();
        Path dir = plugin.dataPath().resolve("reports");
        String version = plugin.getPluginMeta().getVersion();
        plugin.io().execute(() -> {
            CapacityService.Snapshot snap = plugin.capacity().compute(settings.thresholds(), settings.historyDays(), hw,
                    settings.useCalibration());
            ReportWriter.ReportData data = new ReportWriter.ReportData(version, hw, last60, tps, online, snap,
                    findings, spark, settings.thresholds());
            try {
                ReportWriter.Written files = ReportWriter.write(dir, data, m);
                Path root = plugin.getServer().getWorldContainer().toPath().toAbsolutePath();
                String md = root.relativize(files.markdown().toAbsolutePath()).toString();
                String html = root.relativize(files.html().toAbsolutePath()).toString();
                plugin.sync(() -> sender.sendMessage(m.get("report.written", md, html)));
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not write report", e);
                plugin.sync(() -> sender.sendMessage(m.get("report.failed")));
            }
        });
    }

    private void admin(CommandSender sender, String[] args) {
        Messages m = plugin.messages();
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "reload" -> {
                plugin.reload();
                sender.sendMessage(plugin.messages().get("reloaded"));
            }
            case "alerts" -> {
                var a = plugin.settings().alerts();
                sender.sendMessage(m.get("alert.status", m.plain(a.enabled() ? "common.on" : "common.off"),
                        Views.f1(a.msptThreshold()), a.sustainSeconds(),
                        m.plain(a.capacity() ? "common.on" : "common.off"), a.cooldownMinutes(),
                        m.plain(a.discordWebhook().isEmpty() ? "common.off" : "common.on")));
            }
            case "alert-test" -> plugin.alerts().test();
            case "calibration-reset" -> plugin.io().execute(() -> {
                plugin.capacity().clearCalibration();
                plugin.sync(() -> sender.sendMessage(m.get("calibration.cleared")));
            });
            case "profile" -> profile(sender, Arrays.copyOfRange(args, 1, args.length));
            default -> views.adminHelp(sender);
        }
    }

    private void profile(CommandSender sender, String[] args) {
        Messages m = plugin.messages();
        if (!plugin.sparkPresent()) {
            sender.sendMessage(m.get("profile.no-spark"));
            return;
        }
        int seconds = 60;
        if (args.length > 0) {
            try {
                seconds = Math.clamp(Integer.parseInt(args[0]), 10, 600);
            } catch (NumberFormatException e) {
                sender.sendMessage(m.get("profile.usage"));
                return;
            }
        }
        sender.sendMessage(m.get("profile.starting", seconds));
        plugin.getServer().dispatchCommand(sender, "spark profiler start --timeout " + seconds);
    }
}

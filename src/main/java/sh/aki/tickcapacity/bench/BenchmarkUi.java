package sh.aki.tickcapacity.bench;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import sh.aki.tickcapacity.core.estimate.CapacityEstimate;
import sh.aki.tickcapacity.core.estimate.Confidence;
import sh.aki.tickcapacity.core.estimate.Thresholds;
import sh.aki.tickcapacity.display.DisplayService;
import sh.aki.tickcapacity.i18n.Messages;
import sh.aki.tickcapacity.report.EstimateFormatter;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Who hears about a benchmark and how: the operator who started it and online admins get short chat
 * messages and a progress display, the console gets the details.
 */
final class BenchmarkUi {

    private final Server server;
    private final DisplayService displays;
    private Messages messages;
    private UUID initiator;

    BenchmarkUi(Server server, DisplayService displays, Messages messages) {
        this.server = server;
        this.displays = displays;
        this.messages = messages;
    }

    void setMessages(Messages messages) {
        this.messages = messages;
    }

    void setInitiator(UUID initiator) {
        this.initiator = initiator;
    }

    boolean isAudience(Player p) {
        return p.getUniqueId().equals(initiator) || p.hasPermission(BenchmarkRunner.ADMIN_PERMISSION);
    }

    void log(String key, Object... args) {
        server.getConsoleSender().sendMessage(messages.get(key, args));
    }

    /** Progress shown while preparing: chunk loading percentage and the player count being prepared. */
    void showPreparing(Set<DisplayService.Mode> modes, int percent, int players, float progress, String run) {
        show(modes, messages.get("bench.bar.preparing", percent, players, run), progress, BossBar.Color.BLUE);
    }

    void showRunning(Set<DisplayService.Mode> modes, String phase, int players, double mspt, Thresholds th,
                     float progress, String run) {
        BossBar.Color color = mspt < th.comfortableMspt() ? BossBar.Color.GREEN
                : mspt < th.limitMspt() ? BossBar.Color.YELLOW : BossBar.Color.RED;
        show(modes, messages.get("bench.bar.running", phase, players, fmt(mspt),
                String.format(Locale.ROOT, "%.0f", mspt / th.limitMspt() * 100), run), progress, color);
    }

    private void show(Set<DisplayService.Mode> modes, Component text, float progress, BossBar.Color color) {
        if (modes.isEmpty()) {
            return;
        }
        Map<UUID, Set<DisplayService.Mode>> viewers = new HashMap<>();
        for (Player p : server.getOnlinePlayers()) {
            if (isAudience(p)) {
                viewers.put(p.getUniqueId(), modes);
            }
        }
        displays.setViewers(DisplayService.Channel.BENCHMARK, viewers);
        displays.setContent(DisplayService.Channel.BENCHMARK, new DisplayService.Content(text, progress, color));
        displays.render();
    }

    void hide() {
        displays.clear(DisplayService.Channel.BENCHMARK);
    }

    String runLabel(int run, int runs) {
        return runs > 1 ? messages.plain("bench.bar.run", run, runs) : "";
    }

    String phaseLabel(BenchmarkRunner.Phase phase) {
        return messages.plain("bench.phase." + phase.name());
    }

    /** Short summary for chat; every number goes to the console. */
    void announce(CapacityEstimate e, String reasonKey) {
        EstimateFormatter.lines(messages, e).forEach(server.getConsoleSender()::sendMessage);
        Component summary = e.confidence() == Confidence.INSUFFICIENT
                ? messages.get("bench.result.insufficient")
                : messages.get("bench.result.summary", EstimateFormatter.players(e.recommended().point()),
                EstimateFormatter.players(e.limit().point()), messages.plain("confidence." + e.confidence().name()),
                EstimateFormatter.range(e.recommended()), EstimateFormatter.range(e.limit()));
        List<Component> lines = List.of(messages.get("bench.result.title"),
                messages.get("bench.result.reason", messages.plain(reasonKey)), summary,
                messages.get("bench.result.buttons"));
        for (Player p : server.getOnlinePlayers()) {
            if (isAudience(p)) {
                lines.forEach(p::sendMessage);
            }
        }
    }

    static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}

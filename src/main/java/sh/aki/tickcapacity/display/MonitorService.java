package sh.aki.tickcapacity.display;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import sh.aki.tickcapacity.capacity.CapacityService;
import sh.aki.tickcapacity.core.estimate.CapacityEstimate;
import sh.aki.tickcapacity.core.estimate.Confidence;
import sh.aki.tickcapacity.core.estimate.Thresholds;
import sh.aki.tickcapacity.core.model.TickSummary;
import sh.aki.tickcapacity.display.DisplayService.Channel;
import sh.aki.tickcapacity.display.DisplayService.Mode;
import sh.aki.tickcapacity.i18n.Messages;
import sh.aki.tickcapacity.monitor.TickMonitor;
import sh.aki.tickcapacity.report.EstimateFormatter;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/** The /tc monitor display. The choice is stored on the player, so it survives relogs. */
public final class MonitorService implements Listener {

    public static final String PERMISSION = "tickcapacity.use";

    private final DisplayService displays;
    private final TickMonitor ticks;
    private final CapacityService capacity;
    private final NamespacedKey key;
    private final org.bukkit.Server server;
    private final Supplier<Messages> messages;
    private final Supplier<Thresholds> thresholds;

    public MonitorService(Plugin plugin, DisplayService displays, TickMonitor ticks, CapacityService capacity,
                          Supplier<Messages> messages, Supplier<Thresholds> thresholds) {
        this.server = plugin.getServer();
        this.displays = displays;
        this.ticks = ticks;
        this.capacity = capacity;
        this.messages = messages;
        this.thresholds = thresholds;
        this.key = new NamespacedKey(plugin, "monitor");
    }

    public Set<Mode> modes(Player p) {
        return displays.modesOf(Channel.MONITOR, p.getUniqueId());
    }

    public void setModes(Player p, Set<Mode> modes) {
        displays.setViewer(Channel.MONITOR, p.getUniqueId(), modes);
        if (modes.isEmpty()) {
            p.getPersistentDataContainer().remove(key);
        } else {
            p.getPersistentDataContainer().set(key, PersistentDataType.STRING,
                    modes.stream().map(Mode::name).collect(Collectors.joining(",")));
        }
        displays.render();
    }

    public void tick() {
        if (!displays.hasViewers(Channel.MONITOR)) {
            return;
        }
        Messages m = messages.get();
        Thresholds th = thresholds.get();
        TickSummary recent = ticks.recent(5);
        double mspt = recent.isEmpty() ? 0 : recent.meanMs();
        double tps = ticks.tps(5);
        String color = mspt < th.comfortableMspt() ? "green" : mspt < th.limitMspt() ? "yellow" : "red";
        BossBar.Color barColor = mspt < th.comfortableMspt() ? BossBar.Color.GREEN
                : mspt < th.limitMspt() ? BossBar.Color.YELLOW : BossBar.Color.RED;
        int online = server.getOnlinePlayers().size();

        var text = capacityText(m, online)
                .map(cap -> m.get("monitor.line.capacity", color, fmt(tps), fmt(mspt), online, cap))
                .orElseGet(() -> m.get("monitor.line", color, fmt(tps), fmt(mspt), online));
        displays.setContent(Channel.MONITOR,
                new DisplayService.Content(text, (float) Math.min(1, mspt / th.limitMspt()), barColor));
    }

    private Optional<String> capacityText(Messages m, int online) {
        CapacityService.Snapshot s = capacity.snapshot();
        if (s == null) {
            return Optional.empty();
        }
        Optional<CapacityEstimate> best = s.best();
        if (best.isEmpty() || !Double.isFinite(best.get().recommended().point())) {
            return Optional.empty();
        }
        String rec = EstimateFormatter.players(best.get().recommended().point());
        return Optional.of((best.get().confidence() == Confidence.LOW ? "~" : "") + rec);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        String stored = p.getPersistentDataContainer().get(key, PersistentDataType.STRING);
        if (stored == null || !p.hasPermission(PERMISSION)) {
            return;
        }
        Set<Mode> modes = EnumSet.noneOf(Mode.class);
        for (String s : stored.split(",")) {
            Mode.parse(s).ifPresent(modes::add);
        }
        displays.setViewer(Channel.MONITOR, p.getUniqueId(), modes);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        displays.forget(event.getPlayer().getUniqueId());
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%.1f", v);
    }
}

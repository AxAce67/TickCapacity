package sh.aki.tickcapacity.display;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Shows status lines in the boss bar, action bar and tab list footer. Each channel has its own boss bar,
 * the action bar shows the first channel in declaration order, and the tab footer lists all channels below
 * the footer that was there before.
 */
public final class DisplayService {

    public enum Mode {
        BOSSBAR, ACTIONBAR, TAB;

        public static Optional<Mode> parse(String s) {
            try {
                return Optional.of(valueOf(s.trim().toUpperCase(Locale.ROOT).replace("-", "").replace("_", "")));
            } catch (IllegalArgumentException e) {
                return Optional.empty();
            }
        }
    }

    public enum Channel { BENCHMARK, MONITOR }

    public record Content(Component text, float progress, BossBar.Color color) {
    }

    private final Server server;
    private final Map<Channel, BossBar> bars = new EnumMap<>(Channel.class);
    private final Map<Channel, Content> contents = new EnumMap<>(Channel.class);
    private final Map<Channel, Map<UUID, Set<Mode>>> viewers = new EnumMap<>(Channel.class);
    private final Map<Channel, Set<UUID>> barShownTo = new EnumMap<>(Channel.class);
    private final Map<UUID, Component> originalFooter = new HashMap<>();

    public DisplayService(Server server) {
        this.server = server;
        for (Channel c : Channel.values()) {
            bars.put(c, BossBar.bossBar(Component.empty(), 0f, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS));
            viewers.put(c, new HashMap<>());
            barShownTo.put(c, new HashSet<>());
        }
    }

    public void setContent(Channel channel, Content content) {
        contents.put(channel, content);
    }

    public void setViewers(Channel channel, Map<UUID, Set<Mode>> modesByPlayer) {
        viewers.put(channel, new HashMap<>(modesByPlayer));
    }

    public void setViewer(Channel channel, UUID player, Set<Mode> modes) {
        if (modes.isEmpty()) {
            viewers.get(channel).remove(player);
        } else {
            viewers.get(channel).put(player, EnumSet.copyOf(modes));
        }
    }

    public Set<Mode> modesOf(Channel channel, UUID player) {
        return viewers.get(channel).getOrDefault(player, Set.of());
    }

    public boolean hasViewers(Channel channel) {
        return !viewers.get(channel).isEmpty();
    }

    public void clear(Channel channel) {
        viewers.get(channel).clear();
        contents.remove(channel);
        render();
    }

    public void render() {
        for (Channel c : Channel.values()) {
            Content content = contents.get(c);
            if (content != null) {
                BossBar bar = bars.get(c);
                bar.name(content.text());
                bar.progress(Math.clamp(content.progress(), 0f, 1f));
                bar.color(content.color());
            }
        }
        for (Player p : server.getOnlinePlayers()) {
            renderFor(p);
        }
    }

    private void renderFor(Player p) {
        UUID id = p.getUniqueId();
        Component actionBar = null;
        List<Component> tabLines = new ArrayList<>();
        for (Channel c : Channel.values()) {
            Content content = contents.get(c);
            Set<Mode> modes = content == null ? Set.of() : viewers.get(c).getOrDefault(id, Set.of());
            BossBar bar = bars.get(c);
            if (modes.contains(Mode.BOSSBAR)) {
                p.showBossBar(bar); // idempotent; also re-shows after a rejoin
                barShownTo.get(c).add(id);
            } else if (barShownTo.get(c).remove(id)) {
                p.hideBossBar(bar);
            }
            if (content != null && modes.contains(Mode.ACTIONBAR) && actionBar == null) {
                actionBar = content.text();
            }
            if (content != null && modes.contains(Mode.TAB)) {
                tabLines.add(content.text());
            }
        }
        if (actionBar != null) {
            p.sendActionBar(actionBar); // re-sent every second; the client fades it after ~3 s
        }
        if (!tabLines.isEmpty()) {
            if (!originalFooter.containsKey(id)) {
                Component current = p.playerListFooter();
                originalFooter.put(id, current == null ? Component.empty() : current);
            }
            Component footer = originalFooter.get(id);
            boolean first = Component.empty().equals(footer);
            for (Component line : tabLines) {
                footer = first ? line : footer.append(Component.newline()).append(line);
                first = false;
            }
            p.sendPlayerListFooter(footer);
        } else if (originalFooter.containsKey(id)) {
            p.sendPlayerListFooter(originalFooter.remove(id));
        }
    }

    public void forget(UUID player) {
        originalFooter.remove(player);
        for (Channel c : Channel.values()) {
            barShownTo.get(c).remove(player);
        }
    }

    public void shutdown() {
        for (Channel c : Channel.values()) {
            viewers.get(c).clear();
            contents.remove(c);
        }
        render();
    }
}

package sh.aki.tickcapacity.alert;

import com.google.gson.Gson;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import sh.aki.tickcapacity.capacity.CapacityService;
import sh.aki.tickcapacity.config.Settings;
import sh.aki.tickcapacity.core.analysis.AlertEvaluator;
import sh.aki.tickcapacity.core.estimate.CapacityEstimate;
import sh.aki.tickcapacity.core.estimate.Confidence;
import sh.aki.tickcapacity.core.model.TickSummary;
import sh.aki.tickcapacity.i18n.Messages;
import sh.aki.tickcapacity.monitor.TickMonitor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.logging.Logger;

/**
 * Alerts for admins, the console and an optional Discord webhook. Capacity alerts only use estimates of
 * MEDIUM confidence or better.
 */
public final class AlertService {

    public static final String PERMISSION = "tickcapacity.admin";

    private final Server server;
    private final TickMonitor ticks;
    private final CapacityService capacity;
    private final BooleanSupplier benchmarkRunning;
    private final Logger logger;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private Settings.Alerts config;
    private Messages messages;
    private AlertEvaluator evaluator;

    public AlertService(Server server, TickMonitor ticks, CapacityService capacity, BooleanSupplier benchmarkRunning,
                        Logger logger) {
        this.server = server;
        this.ticks = ticks;
        this.capacity = capacity;
        this.benchmarkRunning = benchmarkRunning;
        this.logger = logger;
    }

    public void configure(Settings.Alerts config, Messages messages) {
        this.config = config;
        this.messages = messages;
        this.evaluator = new AlertEvaluator(config.msptThreshold(), config.sustainSeconds() * 1000L,
                config.cooldownMinutes() * 60_000L);
    }

    public void tick() {
        if (config == null || !config.enabled() || benchmarkRunning.getAsBoolean() || sh.aki.tickcapacity.compat.ServerCompat.isPaused(server)) {
            return; // the benchmark raises MSPT on purpose
        }
        TickSummary recent = ticks.recent(30);
        double recommended = Double.NaN;
        double limit = Double.NaN;
        if (config.capacity()) {
            CapacityService.Snapshot s = capacity.snapshot();
            Optional<CapacityEstimate> best = s == null ? Optional.empty() : s.best();
            if (best.isPresent() && best.get().confidence().compareTo(Confidence.MEDIUM) >= 0) {
                recommended = best.get().recommended().point();
                limit = best.get().limit().point();
            }
        }
        List<AlertEvaluator.Alert> alerts = evaluator.update(System.currentTimeMillis(),
                recent.isEmpty() ? Double.NaN : recent.meanMs(), server.getOnlinePlayers().size(), recommended, limit);
        for (AlertEvaluator.Alert a : alerts) {
            send(a);
        }
    }

    private void send(AlertEvaluator.Alert a) {
        String key = "alert." + a.type().name().toLowerCase(Locale.ROOT).replace('_', '-');
        String value = a.type() == AlertEvaluator.Type.MSPT_HIGH || a.type() == AlertEvaluator.Type.MSPT_RECOVERED
                ? String.format(Locale.ROOT, "%.1f", a.value()) : String.format(Locale.ROOT, "%.0f", a.value());
        send(key, value, String.format(Locale.ROOT, "%.0f", a.threshold()));
    }

    private void send(String key, String value, String threshold) {
        var component = messages.get(key, value, threshold);
        server.getConsoleSender().sendMessage(component);
        for (Player p : server.getOnlinePlayers()) {
            if (p.hasPermission(PERMISSION)) {
                p.sendMessage(component);
            }
        }
        if (!config.discordWebhook().isEmpty()) {
            postDiscord(messages.plain(key, value, threshold));
        }
    }

    private void postDiscord(String text) {
        // Never ping @everyone or roles.
        String body = new Gson().toJson(Map.of("content", "[TickCapacity] " + text,
                "allowed_mentions", Map.of("parse", List.of())));
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.discordWebhook()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("User-Agent", "TickCapacity")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((res, err) -> {
            if (err != null) {
                logger.warning("Discord webhook failed: " + err.getMessage());
            } else if (res.statusCode() >= 300) {
                logger.warning("Discord webhook returned HTTP " + res.statusCode());
            }
        });
    }

    public void test() {
        send("alert.test", "", String.format(Locale.ROOT, "%.0f", config.msptThreshold()));
    }
}

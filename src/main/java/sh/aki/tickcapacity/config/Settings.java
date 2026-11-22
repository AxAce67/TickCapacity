package sh.aki.tickcapacity.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import sh.aki.tickcapacity.core.estimate.Thresholds;
import sh.aki.tickcapacity.display.DisplayService.Mode;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public record Settings(
        String language,
        Sampling sampling,
        Thresholds thresholds,
        int historyDays,
        boolean useCalibration,
        Benchmark benchmark,
        Monitor monitor,
        Alerts alerts
) {

    public record Monitor(Set<Mode> defaultModes) {
    }

    public record Alerts(boolean enabled, double msptThreshold, int sustainSeconds, boolean capacity,
                         int cooldownMinutes, String discordWebhook) {
    }

    public record Sampling(boolean enabled, int intervalSeconds, int startupGraceSeconds, int retentionDays) {
    }

    public record Benchmark(
            String worldName,
            long seed,
            boolean keepWorld,
            int maxVirtualPlayers,
            int playersPerStage,
            int settleSeconds,
            int stageSeconds,
            int chunkRadius,
            double abortMspt,
            boolean requireEmptyServer,
            Map<EntityType, Integer> entitiesPerPlayer,
            Set<Mode> progressDisplay,
            double movementBlocksPerSecond,
            int maxRepetitions
    ) {
    }

    /** Out-of-range values are clamped and reported in {@code warnings}. */
    public static Settings load(FileConfiguration c, List<String> warnings) {
        Sampling sampling = new Sampling(
                c.getBoolean("sampling.enabled", true),
                clamp(c.getInt("sampling.interval-seconds", 10), 2, 300, "sampling.interval-seconds", warnings),
                clamp(c.getInt("sampling.startup-grace-seconds", 180), 0, 3600, "sampling.startup-grace-seconds",
                        warnings),
                clamp(c.getInt("sampling.retention-days", 30), 1, 3650, "sampling.retention-days", warnings));

        double limit = c.getDouble("estimation.limit-mspt", 50.0);
        double comfortable = c.getDouble("estimation.comfortable-mspt", 30.0);
        if (!(comfortable > 0 && comfortable <= limit)) {
            warnings.add("estimation.comfortable-mspt must be in (0, limit-mspt]; using 30 / 50");
            limit = 50.0;
            comfortable = 30.0;
        }
        double confidence = c.getDouble("estimation.confidence-level", 0.95);
        if (!(confidence > 0.5 && confidence < 1)) {
            warnings.add("estimation.confidence-level must be in (0.5, 1); using 0.95");
            confidence = 0.95;
        }
        Thresholds thresholds = new Thresholds(limit, comfortable,
                c.getDouble("estimation.comfortable-p95-mspt", 50.0), confidence,
                clamp(c.getInt("estimation.min-samples", 30), 5, 100_000, "estimation.min-samples", warnings),
                clamp(c.getInt("estimation.min-samples-per-player-count", 3), 1, 1000,
                        "estimation.min-samples-per-player-count", warnings),
                clamp(c.getInt("estimation.group-weight-cap", 20), 1, 100_000, "estimation.group-weight-cap",
                        warnings));

        Map<EntityType, Integer> entities = new LinkedHashMap<>();
        // getConfigurationSection() creates an empty section for a missing key instead of using the default,
        // which used to remove all benchmark mobs for configs written before this key existed.
        String entitiesPath = "benchmark.entities-per-player";
        ConfigurationSection es = c.contains(entitiesPath, true) ? c.getConfigurationSection(entitiesPath)
                : c.getDefaults() != null ? c.getDefaults().getConfigurationSection(entitiesPath) : null;
        if (es != null) {
            for (String key : es.getKeys(false)) {
                EntityType type = parseEntity(key);
                if (type == null || !type.isSpawnable() || !type.isAlive()) {
                    warnings.add("benchmark.entities-per-player: unknown or non-living entity '" + key + "'");
                    continue;
                }
                int count = es.getInt(key);
                if (count > 0) {
                    entities.put(type, Math.min(count, 500));
                }
            }
        }

        String worldName = c.getString("benchmark.world-name", "tickcapacity_benchmark");
        if (worldName == null || !worldName.matches("[a-z0-9_.-]{1,64}")) {
            warnings.add("benchmark.world-name must match [a-z0-9_.-]{1,64}; using tickcapacity_benchmark");
            worldName = "tickcapacity_benchmark";
        }
        Benchmark benchmark = new Benchmark(
                worldName,
                c.getLong("benchmark.seed", 20261122L),
                c.getBoolean("benchmark.keep-world", true),
                clamp(c.getInt("benchmark.max-virtual-players", 40), 1, 500, "benchmark.max-virtual-players",
                        warnings),
                clamp(c.getInt("benchmark.players-per-stage", 0), 0, 50, "benchmark.players-per-stage", warnings),
                clamp(c.getInt("benchmark.settle-seconds", 5), 2, 120, "benchmark.settle-seconds", warnings),
                clamp(c.getInt("benchmark.stage-seconds", 20), 5, 600, "benchmark.stage-seconds", warnings),
                clamp(c.getInt("benchmark.chunk-radius", -1), -1, 32, "benchmark.chunk-radius", warnings),
                Math.max(limit, c.getDouble("benchmark.abort-mspt", 80.0)),
                c.getBoolean("benchmark.require-empty-server", true),
                Collections.unmodifiableMap(entities),
                modes(c.getStringList("benchmark.progress-display"), Set.of(Mode.BOSSBAR),
                        "benchmark.progress-display", warnings),
                Math.clamp(c.getDouble("benchmark.movement-blocks-per-second", 0), 0, 20),
                clamp(c.getInt("benchmark.max-repetitions", 5), 1, 20, "benchmark.max-repetitions", warnings));

        Monitor monitor = new Monitor(modes(c.getStringList("monitor.default-display"), Set.of(Mode.BOSSBAR),
                "monitor.default-display", warnings));

        String webhook = c.getString("alerts.discord-webhook", "");
        webhook = webhook == null ? "" : webhook.trim();
        if (!webhook.isEmpty() && !webhook.matches("https://(canary\\.|ptb\\.)?discord(app)?\\.com/api/webhooks/\\S+")) {
            warnings.add("alerts.discord-webhook is not a Discord webhook URL; webhook disabled");
            webhook = "";
        }
        Alerts alerts = new Alerts(
                c.getBoolean("alerts.enabled", true),
                Math.max(1, c.getDouble("alerts.mspt-threshold", 45.0)),
                clamp(c.getInt("alerts.sustain-seconds", 30), 5, 3600, "alerts.sustain-seconds", warnings),
                c.getBoolean("alerts.capacity", true),
                clamp(c.getInt("alerts.cooldown-minutes", 10), 1, 1440, "alerts.cooldown-minutes", warnings),
                webhook);

        return new Settings(c.getString("language", "auto"), sampling, thresholds,
                clamp(c.getInt("estimation.history-days", 14), 1, 3650, "estimation.history-days", warnings),
                c.getBoolean("estimation.use-calibration", true), benchmark, monitor, alerts);
    }

    private static Set<Mode> modes(List<String> names, Set<Mode> fallback, String path, List<String> warnings) {
        if (names.isEmpty()) {
            return fallback;
        }
        Set<Mode> out = EnumSet.noneOf(Mode.class);
        for (String n : names) {
            var mode = Mode.parse(n);
            if (mode.isPresent()) {
                out.add(mode.get());
            } else if (!n.equalsIgnoreCase("none")) {
                warnings.add(path + ": unknown display '" + n + "' (use bossbar, actionbar, tab or none)");
            }
        }
        return Collections.unmodifiableSet(out);
    }

    private static EntityType parseEntity(String name) {
        try {
            return EntityType.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int clamp(int value, int min, int max, String path, List<String> warnings) {
        if (value < min || value > max) {
            int clamped = Math.max(min, Math.min(max, value));
            warnings.add(path + " = " + value + " is out of range [" + min + ", " + max + "]; using " + clamped);
            return clamped;
        }
        return value;
    }
}

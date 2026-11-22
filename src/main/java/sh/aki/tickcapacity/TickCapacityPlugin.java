package sh.aki.tickcapacity;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import sh.aki.tickcapacity.alert.AlertService;
import sh.aki.tickcapacity.bench.BenchmarkRunner;
import sh.aki.tickcapacity.bench.ResultRepository;
import sh.aki.tickcapacity.capacity.CapacityService;
import sh.aki.tickcapacity.command.TickCapacityCommand;
import sh.aki.tickcapacity.compat.ServerCompat;
import sh.aki.tickcapacity.config.Settings;
import sh.aki.tickcapacity.display.DisplayService;
import sh.aki.tickcapacity.display.MonitorService;
import sh.aki.tickcapacity.i18n.Messages;
import sh.aki.tickcapacity.monitor.ChunkGenerationCounter;
import sh.aki.tickcapacity.monitor.HardwareInfo;
import sh.aki.tickcapacity.monitor.JvmMetrics;
import sh.aki.tickcapacity.monitor.TickMonitor;
import sh.aki.tickcapacity.sampling.SampleRepository;
import sh.aki.tickcapacity.sampling.Sampler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class TickCapacityPlugin extends JavaPlugin {

    private static final long CAPACITY_REFRESH_TICKS = 20L * 60 * 5;

    private ExecutorService io;
    private TickMonitor tickMonitor;
    private ChunkGenerationCounter chunkCounter;
    private JvmMetrics jvm;
    private SampleRepository samples;
    private ResultRepository results;
    private CapacityService capacity;
    private DisplayService displays;
    private MonitorService monitorService;
    private AlertService alerts;
    private Settings settings;
    private Messages messages;
    private Sampler sampler;
    private BukkitTask samplerTask;
    private BenchmarkRunner benchmark;
    private volatile String cpuModel = "unknown";
    private volatile boolean benchmarkEarActive = true;

    @Override
    public void onEnable() {
        io = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "TickCapacity-IO");
            t.setDaemon(true);
            return t;
        });
        io.execute(() -> cpuModel = HardwareInfo.detectCpuModel());

        saveDefaultConfig();
        loadSettings();

        tickMonitor = new TickMonitor();
        chunkCounter = new ChunkGenerationCounter();
        jvm = new JvmMetrics(Thread.currentThread());
        samples = new SampleRepository(dataPath().resolve("samples"), getLogger());
        results = new ResultRepository(dataPath().resolve("results"), getLogger());
        capacity = new CapacityService(samples, results, dataPath().resolve("calibration.json"), getLogger());
        getLogger().info("Server features: " + ServerCompat.describe());
        displays = new DisplayService(getServer());
        monitorService = new MonitorService(this, displays, tickMonitor, capacity, () -> messages,
                () -> settings.thresholds());
        getServer().getPluginManager().registerEvents(tickMonitor, this);
        getServer().getPluginManager().registerEvents(chunkCounter, this);
        getServer().getPluginManager().registerEvents(monitorService, this);

        benchmark = new BenchmarkRunner(this, tickMonitor, jvm, chunkCounter, results, displays, io, this::hardware,
                () -> settings.useCalibration() && capacity.snapshot() != null ? capacity.snapshot().calibration() : null,
                this::refreshCapacityAsync, settings, messages);
        alerts = new AlertService(getServer(), tickMonitor, capacity, benchmark::isRunning, getLogger());
        alerts.configure(settings.alerts(), messages);
        startSampler();
        checkActivationRangeAsync();

        getServer().getScheduler().runTaskTimer(this, () -> {
            monitorService.tick();
            alerts.tick();
            displays.render();
        }, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, this::refreshCapacityAsync, 20L * 30, CAPACITY_REFRESH_TICKS);

        TickCapacityCommand command = new TickCapacityCommand(this);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register("tickcapacity", "TickCapacity performance & capacity tools",
                        List.of("tc"), command));
    }

    @Override
    public void onDisable() {
        if (benchmark != null) {
            benchmark.shutdown();
        }
        if (displays != null) {
            displays.shutdown();
        }
        stopSampler();
        if (io != null) {
            io.shutdown();
            try {
                if (!io.awaitTermination(5, TimeUnit.SECONDS)) {
                    getLogger().warning("I/O tasks did not finish in time");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** A running benchmark keeps the settings it started with. */
    public void reload() {
        reloadConfig();
        loadSettings();
        benchmark.reconfigure(settings, messages);
        alerts.configure(settings.alerts(), messages);
        stopSampler();
        startSampler();
        refreshCapacityAsync();
        checkActivationRangeAsync();
    }

    private void checkActivationRangeAsync() {
        String world = settings.benchmark().worldName();
        io.execute(() -> benchmarkEarActive = sh.aki.tickcapacity.bench.ActivationRangeCheck.isActive(
                java.nio.file.Path.of("spigot.yml"), world));
    }

    public boolean benchmarkEarActive() {
        return benchmarkEarActive;
    }

    public boolean sparkPresent() {
        org.bukkit.plugin.Plugin spark = getServer().getPluginManager().getPlugin("spark");
        return spark != null && spark.isEnabled();
    }

    private void loadSettings() {
        List<String> warnings = new ArrayList<>();
        settings = Settings.load(getConfig(), warnings);
        warnings.forEach(w -> getLogger().warning("config.yml: " + w));
        messages = new Messages(settings.language());
    }

    private void startSampler() {
        Settings.Sampling s = settings.sampling();
        sampler = new Sampler(getServer(), tickMonitor, jvm, chunkCounter, samples, io, benchmark::isRunning,
                s.startupGraceSeconds(), s.enabled());
        long period = s.intervalSeconds() * 20L;
        samplerTask = getServer().getScheduler().runTaskTimer(this, sampler, period, period);
        io.execute(() -> samples.purge(s.retentionDays()));
    }

    private void stopSampler() {
        if (samplerTask != null) {
            samplerTask.cancel();
            samplerTask = null;
        }
        if (sampler != null) {
            sampler.close();
        }
    }

    /** Safe to call from any thread. */
    private void refreshCapacityAsync() {
        if (!isEnabled()) {
            return;
        }
        if (!getServer().isPrimaryThread()) {
            sync(this::refreshCapacityAsync); // hardware info must be read on the main thread
            return;
        }
        Settings s = settings;
        HardwareInfo hw = hardware();
        io.execute(() -> {
            try {
                capacity.compute(s.thresholds(), s.historyDays(), hw, s.useCalibration());
            } catch (RuntimeException e) {
                getLogger().log(Level.WARNING, "Capacity refresh failed", e);
            }
        });
    }

    /** getDataPath() only exists on newer servers. */
    public java.nio.file.Path dataPath() {
        return getDataFolder().toPath();
    }

    /** Main thread only. */
    public HardwareInfo hardware() {
        return HardwareInfo.collect(getServer(), jvm, cpuModel);
    }

    public void sync(Runnable task) {
        if (isEnabled()) {
            getServer().getScheduler().runTask(this, task);
        }
    }

    public ExecutorService io() {
        return io;
    }

    public TickMonitor tickMonitor() {
        return tickMonitor;
    }

    public JvmMetrics jvm() {
        return jvm;
    }

    public SampleRepository samples() {
        return samples;
    }

    public ResultRepository results() {
        return results;
    }

    public CapacityService capacity() {
        return capacity;
    }

    public MonitorService monitorService() {
        return monitorService;
    }

    public AlertService alerts() {
        return alerts;
    }

    public Settings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public Sampler sampler() {
        return sampler;
    }

    public BenchmarkRunner benchmark() {
        return benchmark;
    }
}

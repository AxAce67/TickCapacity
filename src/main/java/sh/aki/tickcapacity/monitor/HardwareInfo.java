package sh.aki.tickcapacity.monitor;

import org.bukkit.Server;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Only -X/-XX JVM flags are kept: system properties can contain paths or secrets. */
public record HardwareInfo(
        String cpuModel,
        int availableProcessors,
        long physicalMemoryMb,
        long maxHeapMb,
        String osName,
        String osVersion,
        String osArch,
        String javaVersion,
        String javaVendor,
        String vmName,
        List<String> garbageCollectors,
        List<String> jvmFlags,
        String serverVersion,
        String minecraftVersion,
        int viewDistance,
        int simulationDistance,
        long diskFreeMb
) {

    /** Main thread only. */
    public static HardwareInfo collect(Server server, JvmMetrics jvm, String cpuModel) {
        List<String> flags = ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .filter(a -> a.startsWith("-XX:") || (a.startsWith("-X") && !a.startsWith("-Xbootclasspath")))
                .toList();
        long diskFree = server.getWorldContainer().getUsableSpace() / (1024 * 1024);
        return new HardwareInfo(
                cpuModel,
                Runtime.getRuntime().availableProcessors(),
                jvm.totalPhysicalMemoryMb(),
                jvm.heapMaxMb(),
                System.getProperty("os.name", "?"),
                System.getProperty("os.version", "?"),
                System.getProperty("os.arch", "?"),
                System.getProperty("java.version", "?"),
                System.getProperty("java.vendor", "?"),
                System.getProperty("java.vm.name", "?"),
                jvm.collectorNames(),
                flags,
                server.getVersion(),
                server.getMinecraftVersion(),
                server.getViewDistance(),
                server.getSimulationDistance(),
                diskFree);
    }

    /** Blocking (may run sysctl); keep it off the main thread. */
    public static String detectCpuModel() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("linux")) {
                Path cpuinfo = Path.of("/proc/cpuinfo");
                if (Files.isReadable(cpuinfo)) {
                    for (String line : Files.readAllLines(cpuinfo, StandardCharsets.UTF_8)) {
                        if (line.startsWith("model name") || line.startsWith("Model") || line.startsWith("Hardware")) {
                            int idx = line.indexOf(':');
                            if (idx > 0) {
                                return line.substring(idx + 1).trim();
                            }
                        }
                    }
                }
            } else if (os.contains("mac")) {
                Process p = new ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string")
                        .redirectErrorStream(true).start();
                if (p.waitFor(2, TimeUnit.SECONDS)) {
                    String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                    if (!out.isEmpty()) {
                        return out;
                    }
                } else {
                    p.destroyForcibly();
                }
            } else if (os.contains("windows")) {
                String id = System.getenv("PROCESSOR_IDENTIFIER");
                if (id != null && !id.isBlank()) {
                    return id.trim();
                }
            }
        } catch (IOException e) {
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return "unknown";
    }
}

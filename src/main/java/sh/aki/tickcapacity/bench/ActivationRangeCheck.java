package sh.aki.tickcapacity.bench;

import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Whether entity activation range applies to the benchmark world. With no players around, it makes the
 * virtual players' mobs tick less than real ones would. spigot.yml is only read.
 */
public final class ActivationRangeCheck {

    private ActivationRangeCheck() {
    }

    /** A missing file means the default, which is enabled. */
    public static boolean isActive(Path spigotYml, String world) {
        if (!Files.isRegularFile(spigotYml)) {
            return true; // Spigot/Paper default is enabled
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(spigotYml.toFile());
        return range(yml, world, "monsters") > 0 || range(yml, world, "animals") > 0;
    }

    private static int range(YamlConfiguration yml, String world, String type) {
        String specific = "world-settings." + world + ".entity-activation-range." + type;
        if (yml.contains(specific)) {
            return yml.getInt(specific);
        }
        return yml.getInt("world-settings.default.entity-activation-range." + type, 32);
    }
}

package sh.aki.tickcapacity.compat;

import org.bukkit.GameRule;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Mob;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.OptionalInt;

/**
 * Compiled against the oldest supported API (1.20.6). Newer methods are looked up once and used when present;
 * otherwise an older equivalent is used or the feature is skipped.
 */
public final class ServerCompat {

    private static final MethodHandles.Lookup LOOKUP = MethodHandles.publicLookup();

    /** 1.21.2+ */
    private static final MethodHandle IS_PAUSED =
            find(Server.class, "isPaused", MethodType.methodType(boolean.class));
    private static final MethodHandle GET_PAUSE_TIME =
            find(Server.class, "getPauseWhenEmptyTime", MethodType.methodType(int.class));
    private static final MethodHandle SET_PAUSE_TIME =
            find(Server.class, "setPauseWhenEmptyTime", MethodType.methodType(void.class, int.class));
    /** 26.3+ */
    private static final MethodHandle SET_PERSISTENCE_REQUIRED =
            find(Mob.class, "setPersistenceRequired", MethodType.methodType(void.class, boolean.class));
    /** 26.x */
    private static final Class<?> MODERN_GAME_RULES = findClass("org.bukkit.GameRules");

    private ServerCompat() {
    }

    private static MethodHandle find(Class<?> owner, String name, MethodType type) {
        try {
            return LOOKUP.findVirtual(owner, name, type);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            return null;
        }
    }

    private static Class<?> findClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    public static boolean isPaused(Server server) {
        if (IS_PAUSED == null) {
            return false;
        }
        try {
            return (boolean) IS_PAUSED.invoke(server);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Empty if the server has no pause-when-empty setting. */
    public static OptionalInt pauseWhenEmptySeconds(Server server) {
        if (GET_PAUSE_TIME == null) {
            return OptionalInt.empty();
        }
        try {
            return OptionalInt.of((int) GET_PAUSE_TIME.invoke(server));
        } catch (Throwable t) {
            return OptionalInt.empty();
        }
    }

    /** Values below 1 disable pausing. */
    public static void setPauseWhenEmptySeconds(Server server, int seconds) {
        if (SET_PAUSE_TIME == null) {
            return;
        }
        try {
            SET_PAUSE_TIME.invoke(server, seconds);
        } catch (Throwable t) {
        }
    }

    public static void preventDespawn(Mob mob) {
        if (SET_PERSISTENCE_REQUIRED != null) {
            try {
                SET_PERSISTENCE_REQUIRED.invoke(mob, true);
                return;
            } catch (Throwable t) {
            }
        }
        mob.setRemoveWhenFarAway(false);
    }

    public enum Rule {
        SPAWN_MOBS("SPAWN_MOBS"),
        ADVANCE_TIME("ADVANCE_TIME"),
        ADVANCE_WEATHER("ADVANCE_WEATHER");

        private final String modernField;

        Rule(String modernField) {
            this.modernField = modernField;
        }
    }

    /** @return false if the rule does not exist on this version */
    public static boolean setRule(World world, Rule rule, boolean value) {
        GameRule<Boolean> gameRule = modernRule(rule);
        if (gameRule == null) {
            gameRule = legacyRule(rule);
        }
        return gameRule != null && world.setGameRule(gameRule, value);
    }

    @SuppressWarnings("unchecked")
    private static GameRule<Boolean> modernRule(Rule rule) {
        if (MODERN_GAME_RULES == null) {
            return null;
        }
        try {
            Object value = MODERN_GAME_RULES.getField(rule.modernField).get(null);
            return value instanceof GameRule<?> g ? (GameRule<Boolean>) g : null;
        } catch (ReflectiveOperationException | ClassCastException e) {
            return null;
        }
    }

    /** Only reached when the modern constants are missing, so their eventual removal cannot break this. */
    private static GameRule<Boolean> legacyRule(Rule rule) {
        try {
            return switch (rule) {
                case SPAWN_MOBS -> GameRule.DO_MOB_SPAWNING;
                case ADVANCE_TIME -> GameRule.DO_DAYLIGHT_CYCLE;
                case ADVANCE_WEATHER -> GameRule.DO_WEATHER_CYCLE;
            };
        } catch (LinkageError e) {
            return null;
        }
    }

    public static Path worldFolder(World world) {
        return world.getWorldFolder().toPath();
    }

    /** For the startup log. */
    public static String describe() {
        return "pause-when-empty=" + (SET_PAUSE_TIME != null) + ", modern-game-rules=" + (MODERN_GAME_RULES != null)
                + ", mob-persistence-api=" + (SET_PERSISTENCE_REQUIRED != null);
    }
}

package sh.aki.tickcapacity.core.analysis;

import java.util.List;

/** {@code key} selects the message and advice; {@code args} are already formatted. */
public record Finding(Category category, Severity severity, String key, List<String> args) {

    public enum Category {
        HEALTHY, MAIN_THREAD, CPU, MEMORY, GC, ENTITIES, BLOCK_ENTITIES, CHUNKS, SPIKES, HARDWARE
    }

    public enum Severity {
        INFO, WARNING, CRITICAL
    }

    public Finding {
        args = List.copyOf(args);
    }
}

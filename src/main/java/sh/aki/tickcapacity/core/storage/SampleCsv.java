package sh.aki.tickcapacity.core.storage;

import sh.aki.tickcapacity.core.model.Sample;
import sh.aki.tickcapacity.core.model.TickSummary;

import java.util.Locale;
import java.util.Optional;

/** New columns are only ever appended, so older files stay readable. */
public final class SampleCsv {

    public static final String HEADER = "epoch_ms,interval_ms,players,ticks,mspt_mean,mspt_p50,mspt_p95,"
            + "mspt_p99,mspt_max,tick_busy_ms,tps,process_cpu,system_cpu,main_thread_cpu_ms,heap_used_mb,"
            + "heap_max_mb,gc_count,gc_time_ms,loaded_chunks,entities,ticking_block_entities,new_chunks";

    /** Rows written before new_chunks existed. */
    private static final int MIN_COLUMNS = 21;

    private SampleCsv() {
    }

    public static String encode(Sample s) {
        TickSummary t = s.ticks();
        return String.join(",",
                Long.toString(s.epochMillis()),
                Long.toString(s.intervalMs()),
                Integer.toString(s.players()),
                Integer.toString(t.ticks()),
                num(t.meanMs()), num(t.p50Ms()), num(t.p95Ms()), num(t.p99Ms()), num(t.maxMs()), num(t.busyMs()),
                num(s.tps()),
                num(s.processCpu()), num(s.systemCpu()), num(s.mainThreadCpuMs()),
                Long.toString(s.heapUsedMb()), Long.toString(s.heapMaxMb()),
                Long.toString(s.gcCount()), Long.toString(s.gcTimeMs()),
                Integer.toString(s.loadedChunks()), Integer.toString(s.entities()),
                Integer.toString(s.tickingBlockEntities()),
                Integer.toString(s.newChunks()));
    }

    /** Empty for the header and for broken rows (e.g. a crash mid-write) instead of failing the file. */
    public static Optional<Sample> decode(String line) {
        if (line == null || line.isBlank() || line.startsWith("epoch_ms")) {
            return Optional.empty();
        }
        String[] c = line.split(",", -1);
        if (c.length < MIN_COLUMNS) {
            return Optional.empty();
        }
        try {
            TickSummary ticks = new TickSummary(Integer.parseInt(c[3]), dbl(c[4]), dbl(c[5]), dbl(c[6]), dbl(c[7]),
                    dbl(c[8]), dbl(c[9]));
            return Optional.of(new Sample(
                    Long.parseLong(c[0]), Long.parseLong(c[1]), Integer.parseInt(c[2]), ticks,
                    dbl(c[10]), dbl(c[11]), dbl(c[12]), dbl(c[13]),
                    Long.parseLong(c[14]), Long.parseLong(c[15]), Long.parseLong(c[16]), Long.parseLong(c[17]),
                    Integer.parseInt(c[18]), Integer.parseInt(c[19]), Integer.parseInt(c[20]),
                    c.length > 21 && !c[21].isEmpty() ? Integer.parseInt(c[21]) : -1));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String num(double v) {
        if (Double.isNaN(v)) {
            return "";
        }
        return String.format(Locale.ROOT, "%.4f", v);
    }

    private static double dbl(String s) {
        return s.isEmpty() ? Double.NaN : Double.parseDouble(s);
    }
}

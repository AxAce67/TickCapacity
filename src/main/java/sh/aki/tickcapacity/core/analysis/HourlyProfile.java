package sh.aki.tickcapacity.core.analysis;

import sh.aki.tickcapacity.core.model.Sample;
import sh.aki.tickcapacity.core.stats.Stats;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** {@code hours} has 24 entries indexed by local hour; hours without data have no samples. */
public record HourlyProfile(List<Hour> hours, int samples) {

    public record Hour(int hour, int samples, double avgPlayers, int maxPlayers, double medianMspt,
                       double medianP95) {
    }

    public static HourlyProfile of(List<Sample> samples, ZoneId zone) {
        List<List<Sample>> buckets = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            buckets.add(new ArrayList<>());
        }
        int used = 0;
        for (Sample s : samples) {
            if (s.ticks().ticks() == 0 || Double.isNaN(s.ticks().meanMs())) {
                continue;
            }
            int hour = Instant.ofEpochMilli(s.epochMillis()).atZone(zone).getHour();
            buckets.get(hour).add(s);
            used++;
        }
        List<Hour> hours = new ArrayList<>();
        for (int h = 0; h < 24; h++) {
            List<Sample> b = buckets.get(h);
            if (b.isEmpty()) {
                hours.add(new Hour(h, 0, Double.NaN, 0, Double.NaN, Double.NaN));
                continue;
            }
            double avg = b.stream().mapToInt(Sample::players).average().orElse(0);
            int max = b.stream().mapToInt(Sample::players).max().orElse(0);
            double mspt = Stats.median(b.stream().mapToDouble(s -> s.ticks().meanMs()).toArray());
            double[] p95 = b.stream().mapToDouble(s -> s.ticks().p95Ms()).filter(v -> !Double.isNaN(v)).toArray();
            hours.add(new Hour(h, b.size(), avg, max, mspt, p95.length > 0 ? Stats.median(p95) : Double.NaN));
        }
        return new HourlyProfile(List.copyOf(hours), used);
    }

    public Optional<Hour> peakPlayers() {
        return hours.stream().filter(h -> h.samples() > 0).max(Comparator.comparingDouble(Hour::avgPlayers));
    }

    public Optional<Hour> worstMspt() {
        return hours.stream().filter(h -> h.samples() > 0).max(Comparator.comparingDouble(Hour::medianMspt));
    }

    public Optional<Hour> quietest() {
        return hours.stream().filter(h -> h.samples() > 0).min(Comparator.comparingDouble(Hour::avgPlayers));
    }
}

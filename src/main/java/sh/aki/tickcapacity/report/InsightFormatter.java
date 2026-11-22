package sh.aki.tickcapacity.report;

import net.kyori.adventure.text.Component;
import sh.aki.tickcapacity.core.analysis.HourlyProfile;
import sh.aki.tickcapacity.core.estimate.ActivityAnalyzer;
import sh.aki.tickcapacity.core.estimate.Calibration;
import sh.aki.tickcapacity.i18n.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class InsightFormatter {

    private static final char[] SPARK = {'▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'};

    private record Line(String key, Object[] args) {
    }

    private InsightFormatter() {
    }


    public static List<Component> activity(Messages m, ActivityAnalyzer.Result r) {
        return activityLines(r).stream().map(l -> m.get(l.key, l.args)).toList();
    }

    public static List<String> activityPlain(Messages m, ActivityAnalyzer.Result r) {
        return activityLines(r).stream().map(l -> m.plain(l.key, l.args)).toList();
    }

    private static List<Line> activityLines(ActivityAnalyzer.Result r) {
        List<Line> out = new ArrayList<>();
        out.add(new Line("activity.header", new Object[0]));
        if (!r.available()) {
            out.add(new Line(r.reasonKey(), new Object[]{r.samples(), ActivityAnalyzer.MIN_SAMPLES}));
            return out;
        }
        out.add(new Line("activity.model", new Object[]{f2(r.perPlayerMs()), f2(r.perChunkPerSecondMs()),
                f2(r.typicalRatePerPlayer()), f2(r.heavyRatePerPlayer())}));
        for (ActivityAnalyzer.Scenario s : r.scenarios()) {
            out.add(new Line("activity.scenario." + s.key(), new Object[]{
                    EstimateFormatter.players(s.recommended()), EstimateFormatter.players(s.limit()),
                    f2(s.chunksPerSecondPerPlayer())}));
        }
        out.add(new Line("activity.caveat", new Object[0]));
        return out;
    }


    public static List<Component> hourly(Messages m, HourlyProfile p) {
        return hourlyLines(p).stream().map(l -> m.get(l.key, l.args)).toList();
    }

    private static List<Line> hourlyLines(HourlyProfile p) {
        List<Line> out = new ArrayList<>();
        out.add(new Line("peak.header", new Object[]{p.samples()}));
        if (p.peakPlayers().isEmpty()) {
            out.add(new Line("peak.no-data", new Object[0]));
            return out;
        }
        out.add(new Line("peak.players-spark", new Object[]{sparkline(p, true)}));
        out.add(new Line("peak.mspt-spark", new Object[]{sparkline(p, false)}));
        HourlyProfile.Hour peak = p.peakPlayers().get();
        out.add(new Line("peak.peak", new Object[]{peak.hour(), f1(peak.avgPlayers()), peak.maxPlayers(),
                f1(peak.medianMspt())}));
        p.worstMspt().ifPresent(h -> out.add(new Line("peak.worst", new Object[]{h.hour(), f1(h.medianMspt()),
                f1(h.medianP95())})));
        p.quietest().ifPresent(h -> out.add(new Line("peak.quiet", new Object[]{h.hour(), f1(h.avgPlayers())})));
        return out;
    }

    /** One character per hour; a space where there is no data. */
    static String sparkline(HourlyProfile p, boolean players) {
        double max = 0;
        for (HourlyProfile.Hour h : p.hours()) {
            if (h.samples() > 0) {
                max = Math.max(max, players ? h.avgPlayers() : h.medianMspt());
            }
        }
        StringBuilder sb = new StringBuilder(24);
        for (HourlyProfile.Hour h : p.hours()) {
            if (h.samples() == 0) {
                sb.append(' ');
                continue;
            }
            double v = players ? h.avgPlayers() : h.medianMspt();
            int idx = max > 0 ? (int) Math.round(v / max * (SPARK.length - 1)) : 0;
            sb.append(SPARK[Math.clamp(idx, 0, SPARK.length - 1)]);
        }
        return sb.toString();
    }


    public static Component calibration(Messages m, Calibration c) {
        return m.get("calibration.line", f2(c.factor()), String.format(Locale.ROOT, "%.0f", c.relativeStdError() * 100),
                c.benchmarkId());
    }

    public static String calibrationPlain(Messages m, Calibration c) {
        return m.plain("calibration.line", f2(c.factor()),
                String.format(Locale.ROOT, "%.0f", c.relativeStdError() * 100), c.benchmarkId());
    }

    static String f1(double v) {
        return EstimateFormatter.f1(v);
    }

    static String f2(double v) {
        return EstimateFormatter.f2(v);
    }
}

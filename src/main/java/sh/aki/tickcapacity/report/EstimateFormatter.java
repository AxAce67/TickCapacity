package sh.aki.tickcapacity.report;

import net.kyori.adventure.text.Component;
import sh.aki.tickcapacity.core.estimate.CapacityEstimate;
import sh.aki.tickcapacity.core.estimate.Confidence;
import sh.aki.tickcapacity.core.estimate.Range;
import sh.aki.tickcapacity.i18n.Messages;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Player counts are rounded down: 25.8 means 25 fit, not 26. */
public final class EstimateFormatter {

    private record Line(String key, Object[] args) {
    }

    private EstimateFormatter() {
    }

    public static List<Component> lines(Messages m, CapacityEstimate e) {
        return build(m, e).stream().map(l -> m.get(l.key, l.args)).toList();
    }

    public static List<String> plainLines(Messages m, CapacityEstimate e) {
        // Reports add their own list markers.
        return build(m, e).stream().map(l -> m.plain(l.key, l.args).replaceFirst("^•\\s*", "")).toList();
    }

    private static List<Line> build(Messages m, CapacityEstimate e) {
        List<Line> out = new ArrayList<>();
        out.add(line(e.source() == CapacityEstimate.Source.OBSERVED ? "estimate.header.observed"
                : "estimate.header.benchmark"));
        out.add(line("estimate.confidence", m.plain("confidence." + e.confidence().name())));

        if (e.confidence() == Confidence.INSUFFICIENT) {
            out.add(line("estimate.insufficient"));
        } else if (!Double.isFinite(e.recommended().point())) {
            out.add(line("estimate.at-least", players(e.recommended().low())));
        } else {
            out.add(line("estimate.recommended", players(e.recommended().point()), players(e.recommended().low()),
                    players(e.recommended().high())));
            out.add(line("estimate.limit", players(e.limit().point()), players(e.limit().low()),
                    players(e.limit().high())));
            out.add(line("estimate.model", f2(e.perPlayerMs()), f2(e.perPlayerMsStdError()), f1(e.baseMs()),
                    f2(e.rSquared())));
        }
        out.add(line("estimate.data", e.samples(), e.distinctLoadLevels(), e.maxObservedPlayers()));
        if (e.provenComfortablePlayers() > 0) {
            out.add(line("estimate.proven", e.provenComfortablePlayers()));
        }
        for (CapacityEstimate.Note note : e.notesOrEmpty()) {
            String key = "note." + note.key();
            if (m.has(key)) {
                out.add(line(key, noteValue(note)));
            }
        }
        return out;
    }

    static String noteValue(CapacityEstimate.Note note) {
        double v = note.value();
        if (Double.isNaN(v)) {
            return "";
        }
        return switch (note.key()) {
            case "spawns-blocked" -> String.format(Locale.ROOT, "%.0f%%", v * 100);
            case "too-few-samples", "too-few-player-counts", "too-few-stages", "repeated-runs", "memory-limited" ->
                    String.format(Locale.ROOT, "%.0f", v);
            case "moving-players", "short-history" -> String.format(Locale.ROOT, "%.1f", v);
            default -> f2(v);
        };
    }

    /** "25 (20–31)" */
    public static String range(Range r) {
        if (!r.isKnown()) {
            return "-";
        }
        return players(r.point()) + " (" + players(r.low()) + "–" + players(r.high()) + ")";
    }

    public static String players(double v) {
        if (Double.isNaN(v)) {
            return "?";
        }
        if (Double.isInfinite(v)) {
            return "∞";
        }
        return Long.toString((long) Math.floor(Math.max(0, v)));
    }

    private static Line line(String key, Object... args) {
        return new Line(key, args);
    }

    static String f1(double v) {
        return Double.isNaN(v) ? "?" : String.format(Locale.ROOT, "%.1f", v);
    }

    static String f2(double v) {
        return Double.isNaN(v) ? "?" : String.format(Locale.ROOT, "%.2f", v);
    }
}

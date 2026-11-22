package sh.aki.tickcapacity.report;

import sh.aki.tickcapacity.capacity.CapacityService;
import sh.aki.tickcapacity.core.analysis.Finding;
import sh.aki.tickcapacity.core.analysis.HourlyProfile;
import sh.aki.tickcapacity.core.estimate.CapacityEstimate;
import sh.aki.tickcapacity.core.estimate.Confidence;
import sh.aki.tickcapacity.i18n.Messages;

import java.time.LocalDateTime;
import java.util.List;

/** Single-file HTML report with inline SVG charts; follows the light/dark preference. */
final class HtmlReport {

    private static final String CSS = """
            :root{--bg:#f7f8fa;--card:#fff;--fg:#1d2330;--muted:#5f6b7a;--line:#e3e7ec;--accent:#2f6fde;
            --band:rgba(47,111,222,.15);--pt:#2f6fde;--pt2:#c2410c;--warn:#b45309;--crit:#b91c1c;--ok:#15803d;--bar:#94a3b8}
            @media (prefers-color-scheme:dark){:root{--bg:#0f1318;--card:#171c23;--fg:#e6e9ee;--muted:#9aa5b4;
            --line:#2a313b;--accent:#6ea0ff;--band:rgba(110,160,255,.18);--pt:#6ea0ff;--pt2:#fb923c;--warn:#fbbf24;
            --crit:#f87171;--ok:#4ade80;--bar:#475569}}
            *{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);
            font:15px/1.55 system-ui,-apple-system,"Segoe UI","Hiragino Sans","Noto Sans JP",sans-serif}
            main{max-width:960px;margin:0 auto;padding:24px 16px 64px}h1{font-size:1.6rem;margin:0 0 4px}
            h2{font-size:1.15rem;margin:32px 0 12px}.muted{color:var(--muted)}
            .cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(200px,1fr));gap:12px;margin-top:16px}
            .card{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:14px 16px}
            .card .label{color:var(--muted);font-size:.85rem}.card .value{font-size:1.8rem;font-weight:650}
            .card .sub{color:var(--muted);font-size:.85rem}
            .panel{background:var(--card);border:1px solid var(--line);border-radius:10px;padding:16px;margin-top:12px}
            ul{margin:6px 0;padding-left:20px}li{margin:3px 0}
            table{border-collapse:collapse;width:100%;font-size:.9rem}th,td{text-align:left;padding:6px 8px;
            border-bottom:1px solid var(--line)}th{color:var(--muted);font-weight:600}
            .tablewrap{overflow-x:auto}.sev-CRITICAL{color:var(--crit);font-weight:650}
            .sev-WARNING{color:var(--warn);font-weight:650}.sev-INFO{color:var(--accent);font-weight:650}
            .conf-HIGH{color:var(--ok)}.conf-MEDIUM{color:var(--warn)}.conf-LOW,.conf-INSUFFICIENT{color:var(--crit)}
            svg.chart{width:100%;height:auto;display:block}.chart .grid line{stroke:var(--line)}
            .chart .tick,.chart .axis-label{fill:var(--muted);font-size:11px}.chart .axis{stroke:var(--muted)}
            .chart .band{fill:var(--band)}.chart .fit{stroke:var(--accent);stroke-width:2}
            .chart .pts circle{fill:var(--pt);fill-opacity:.85}.chart .pts2 circle{fill:var(--pt2);fill-opacity:.85}
            .chart .line2{stroke:var(--pt2);stroke-width:2}.chart .bars rect{fill:var(--bar)}
            .chart .ref line{stroke-dasharray:5 4;stroke:var(--warn)}.chart .ref text{fill:var(--warn);font-size:11px}
            .chart .crit line{stroke-dasharray:5 4;stroke:var(--crit)}.chart .crit text{fill:var(--crit);font-size:11px}
            .chart .mark line{stroke:var(--ok)}.chart .mark text{fill:var(--ok);font-size:11px}
            .legend{color:var(--muted);font-size:.85rem;margin-top:6px}
            """;

    private HtmlReport() {
    }

    static String render(ReportWriter.ReportData d, Messages m, LocalDateTime now) {
        CapacityService.Snapshot s = d.snapshot();
        StringBuilder sb = new StringBuilder(32_768);
        sb.append("<!doctype html><html lang=\"").append(Html.escape(m.locale().getLanguage()))
                .append("\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(Html.escape(m.plain("report.title"))).append("</title><style>")
                .append(CSS).append("</style></head><body><main>");
        sb.append("<h1>").append(Html.escape(m.plain("report.title"))).append("</h1><div class=\"muted\">")
                .append(Html.escape(m.plain("report.generated", now.withNano(0).toString().replace('T', ' '),
                        d.pluginVersion()))).append("</div>");

        CapacityEstimate best = s.best().orElse(null);
        sb.append("<div class=\"cards\">");
        if (best != null && Double.isFinite(best.recommended().point())) {
            card(sb, m.plain("html.recommended"), EstimateFormatter.players(best.recommended().point()),
                    EstimateFormatter.players(best.recommended().low()) + "–"
                            + EstimateFormatter.players(best.recommended().high()));
            card(sb, m.plain("html.limit"), EstimateFormatter.players(best.limit().point()),
                    EstimateFormatter.players(best.limit().low()) + "–" + EstimateFormatter.players(best.limit().high()));
        } else {
            card(sb, m.plain("html.recommended"), "?", m.plain("html.no-estimate"));
        }
        card(sb, m.plain("html.confidence"), best == null ? m.plain("confidence.INSUFFICIENT")
                        : m.plain("confidence." + best.confidence().name()),
                best == null ? "" : m.plain(best.source() == CapacityEstimate.Source.OBSERVED
                        ? "html.source.observed" : "html.source.benchmark"));
        card(sb, "TPS / MSPT", EstimateFormatter.f1(d.tps60s()), "MSPT " + EstimateFormatter.f1(d.last60s().meanMs())
                + " ms · " + m.plain("report.players") + " " + d.playersOnline());
        sb.append("</div>");

        sb.append("<h2>").append(Html.escape(m.plain("report.section.capacity"))).append("</h2>");
        estimatePanel(sb, m, s.observed(), d);
        if (s.benchmarkEstimate() != null) {
            estimatePanel(sb, m, s.benchmarkEstimate(), d);
        }
        if (s.calibration() != null) {
            sb.append("<div class=\"panel\">").append(Html.escape(InsightFormatter.calibrationPlain(m, s.calibration())))
                    .append("</div>");
        }
        sb.append("<p class=\"muted\">").append(Html.escape(m.plain("report.capacity-disclaimer"))).append("</p>");

        List<String> activity = InsightFormatter.activityPlain(m, s.activity());
        sb.append("<h2>").append(Html.escape(activity.getFirst())).append("</h2><div class=\"panel\"><ul>");
        for (String line : activity.subList(1, activity.size())) {
            sb.append("<li>").append(Html.escape(line)).append("</li>");
        }
        sb.append("</ul></div>");

        sb.append("<h2>").append(Html.escape(m.plain("report.section.hourly"))).append("</h2><div class=\"panel\">");
        hourlyChart(sb, m, s.hourly(), d.thresholds().comfortableMspt());
        sb.append("</div>");

        sb.append("<h2>").append(Html.escape(m.plain("report.section.bottlenecks"))).append("</h2><div class=\"panel\"><ul>");
        for (Finding f : d.findings()) {
            sb.append("<li><span class=\"sev-").append(f.severity().name()).append("\">")
                    .append(Html.escape(m.plain("severity." + f.severity().name()))).append("</span> ")
                    .append(Html.escape(m.plain("finding." + f.key(), f.args().toArray())));
            if (m.has("advice." + f.key())) {
                sb.append("<div class=\"muted\">").append(Html.escape(m.plain("advice." + f.key(), f.args().toArray())))
                        .append("</div>");
            }
            sb.append("</li>");
        }
        if (d.sparkPresent()) {
            sb.append("<li>").append(Html.escape(m.plain("analyze.spark-hint"))).append("</li>");
        }
        sb.append("</ul></div>");

        sb.append("<h2>").append(Html.escape(m.plain("report.section.hardware")))
                .append("</h2><div class=\"panel tablewrap\"><table>");
        for (String[] row : ReportWriter.hardwareRows(d.hardware())) {
            sb.append("<tr><th>").append(Html.escape(row[0])).append("</th><td>").append(Html.escape(row[1]))
                    .append("</td></tr>");
        }
        sb.append("</table></div>");

        sb.append("<h2>").append(Html.escape(m.plain("report.section.method"))).append("</h2><div class=\"panel\">");
        for (String para : m.plain("report.method").split("\n\n")) {
            sb.append("<p>").append(Html.escape(para.replace("**", "").replace("`", ""))).append("</p>");
        }
        sb.append("</div></main></body></html>");
        return sb.toString();
    }

    private static void card(StringBuilder sb, String label, String value, String sub) {
        sb.append("<div class=\"card\"><div class=\"label\">").append(Html.escape(label))
                .append("</div><div class=\"value\">").append(Html.escape(value)).append("</div><div class=\"sub\">")
                .append(Html.escape(sub)).append("</div></div>");
    }

    private static void estimatePanel(StringBuilder sb, Messages m, CapacityEstimate e, ReportWriter.ReportData d) {
        List<String> lines = EstimateFormatter.plainLines(m, e);
        sb.append("<div class=\"panel\"><strong>").append(Html.escape(lines.getFirst())).append("</strong>");
        if (ReportWriter.hasChartData(e)) {
            sb.append(capacityChart(m, e, d));
            sb.append("<div class=\"legend\">").append(Html.escape(m.plain("html.chart-legend"))).append("</div>");
        }
        sb.append("<ul>");
        for (String line : lines.subList(1, lines.size())) {
            String cls = line.equals(lines.get(1)) && e.confidence() != null ? " class=\"conf-" + e.confidence().name()
                    + "\"" : "";
            sb.append("<li").append(cls).append('>').append(Html.escape(line)).append("</li>");
        }
        sb.append("</ul></div>");
    }

    private static String capacityChart(Messages m, CapacityEstimate e, ReportWriter.ReportData d) {
        var points = e.pointsOrEmpty();
        var curve = e.curveOrEmpty();
        double xMax = points.stream().mapToDouble(CapacityEstimate.LoadPoint::players).max().orElse(1);
        double yMax = points.stream().mapToDouble(CapacityEstimate.LoadPoint::meanMs).max().orElse(1);
        for (var c : curve) {
            xMax = Math.max(xMax, c.players());
        }
        double limitMs = d.thresholds().limitMspt();
        yMax = Math.max(yMax, limitMs * 1.1);
        SvgChart chart = new SvgChart(m.plain("html.axis.players"), m.plain("html.axis.mspt"), 0, SvgChart.niceMax(xMax),
                0, SvgChart.niceMax(yMax));
        if (!curve.isEmpty()) {
            double[] cx = curve.stream().mapToDouble(CapacityEstimate.CurvePoint::players).toArray();
            chart.band(cx, curve.stream().mapToDouble(CapacityEstimate.CurvePoint::lowerMs).toArray(),
                    curve.stream().mapToDouble(CapacityEstimate.CurvePoint::upperMs).toArray(), "band");
            chart.line(cx, curve.stream().mapToDouble(CapacityEstimate.CurvePoint::meanMs).toArray(), "fit");
        }
        double[] px = points.stream().mapToDouble(CapacityEstimate.LoadPoint::players).toArray();
        String[] tips = points.stream().map(p -> EstimateFormatter.f1(p.players()) + " → "
                + EstimateFormatter.f1(p.meanMs()) + " ms (p95 " + EstimateFormatter.f1(p.p95Ms()) + ", n=" + p.samples()
                + ")").toArray(String[]::new);
        chart.points(px, points.stream().mapToDouble(CapacityEstimate.LoadPoint::meanMs).toArray(),
                points.stream().mapToDouble(CapacityEstimate.LoadPoint::samples).toArray(), "pts", tips);
        chart.hline(d.thresholds().comfortableMspt(), m.plain("html.comfortable-line"), "ref");
        chart.hline(limitMs, m.plain("html.limit-line"), "crit");
        if (e.confidence() != Confidence.INSUFFICIENT) {
            chart.vline(e.recommended().point(), m.plain("html.recommended") + " "
                    + EstimateFormatter.players(e.recommended().point()), "mark");
        }
        return chart.render(m.plain("html.axis.players") + " / " + m.plain("html.axis.mspt"));
    }

    private static void hourlyChart(StringBuilder sb, Messages m, HourlyProfile p, double comfortable) {
        if (p.peakPlayers().isEmpty()) {
            sb.append("<p class=\"muted\">").append(Html.escape(m.plain("peak.no-data"))).append("</p>");
            return;
        }
        double[] hours = new double[24];
        double[] players = new double[24];
        double[] mspt = new double[24];
        double maxPlayers = 1;
        double maxMspt = 1;
        for (HourlyProfile.Hour h : p.hours()) {
            hours[h.hour()] = h.hour();
            players[h.hour()] = h.samples() > 0 ? h.avgPlayers() : Double.NaN;
            mspt[h.hour()] = h.samples() > 0 ? h.medianMspt() : Double.NaN;
            if (h.samples() > 0) {
                maxPlayers = Math.max(maxPlayers, h.avgPlayers());
                maxMspt = Math.max(maxMspt, h.medianMspt());
            }
        }
        sb.append(new SvgChart(m.plain("html.axis.hour"), m.plain("html.axis.avg-players"), -0.5, 23.5, 0,
                SvgChart.niceMax(maxPlayers)).bars(hours, players, 1, "bars").render(m.plain("report.section.hourly")));
        sb.append(new SvgChart(m.plain("html.axis.hour"), m.plain("html.axis.mspt"), -0.5, 23.5, 0,
                SvgChart.niceMax(Math.max(maxMspt, comfortable))).line(hours, mspt, "line2")
                .points(hours, mspt, new double[24], "pts2", tooltips(p))
                .hline(comfortable, m.plain("html.comfortable-line"), "ref")
                .render(m.plain("report.section.hourly") + " MSPT"));
    }

    private static String[] tooltips(HourlyProfile p) {
        String[] out = new String[24];
        for (HourlyProfile.Hour h : p.hours()) {
            out[h.hour()] = h.hour() + ":00 — " + EstimateFormatter.f1(h.avgPlayers()) + " / max " + h.maxPlayers()
                    + ", MSPT " + EstimateFormatter.f1(h.medianMspt());
        }
        return out;
    }
}

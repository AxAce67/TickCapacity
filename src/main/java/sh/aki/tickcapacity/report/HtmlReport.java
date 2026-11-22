package sh.aki.tickcapacity.report;

import sh.aki.tickcapacity.bench.BenchmarkResult;
import sh.aki.tickcapacity.capacity.CapacityService;
import sh.aki.tickcapacity.core.analysis.Finding;
import sh.aki.tickcapacity.core.analysis.HourlyProfile;
import sh.aki.tickcapacity.core.estimate.ActivityAnalyzer;
import sh.aki.tickcapacity.core.estimate.CapacityEstimate;
import sh.aki.tickcapacity.core.estimate.Confidence;
import sh.aki.tickcapacity.core.estimate.StageResult;
import sh.aki.tickcapacity.core.model.TickSummary;
import sh.aki.tickcapacity.i18n.Messages;
import sh.aki.tickcapacity.monitor.HardwareInfo;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Single-file HTML report with inline SVG charts; follows the light/dark preference. */
final class HtmlReport {

    private static final String CSS = """
            :root{--bg:#ffffff;--fg:#1f2328;--muted:#656d76;--subtle:#8c959f;--line:#e6e8eb;--line-strong:#d0d7de;
            --fill:#f6f8fa;--good:#1a7f37;--warn:#9a6700;--bad:#cf222e;--plot:#57606a;--plot-soft:rgba(87,96,106,.13);
            --bar:#d0d7de}
            @media (prefers-color-scheme:dark){:root{--bg:#0d1117;--fg:#e6edf3;--muted:#9198a1;--subtle:#6e7681;
            --line:#21262d;--line-strong:#30363d;--fill:#161b22;--good:#3fb950;--warn:#d29922;--bad:#f85149;
            --plot:#9198a1;--plot-soft:rgba(145,152,161,.15);--bar:#30363d}}
            *{box-sizing:border-box}
            body{margin:0;background:var(--bg);color:var(--fg);font:14px/1.6 -apple-system,BlinkMacSystemFont,
            "Segoe UI","Hiragino Sans","Hiragino Kaku Gothic ProN","Noto Sans JP","Yu Gothic UI",Meiryo,sans-serif}
            .n,td.num,.figure .value,.facts dd{font-variant-numeric:tabular-nums}
            main{max-width:880px;margin:0 auto;padding:28px 24px 72px}
            .top{display:flex;justify-content:space-between;align-items:baseline;gap:16px;flex-wrap:wrap;
            padding-bottom:14px;border-bottom:1px solid var(--line)}
            .brand{font-weight:600;font-size:14px}.meta{color:var(--muted);font-size:12px}
            h1{font-size:26px;font-weight:600;line-height:1.35;margin:28px 0 20px;letter-spacing:-.01em}
            .chip{display:inline-block;font-size:12px;font-weight:500;line-height:20px;padding:0 8px;border-radius:10px;
            border:1px solid currentColor;vertical-align:5px;margin-left:8px}
            .chip.HIGH{color:var(--good)}.chip.MEDIUM{color:var(--warn)}.chip.LOW,.chip.INSUFFICIENT{color:var(--bad)}
            .figures{display:grid;grid-template-columns:repeat(4,1fr);border:1px solid var(--line);border-radius:6px;
            background:var(--fill)}
            .figure{padding:14px 16px;border-left:1px solid var(--line)}.figure:first-child{border-left:0}
            .figure .label{font-size:12px;color:var(--muted)}
            .figure .value{font-size:24px;font-weight:600;line-height:1.3;margin-top:2px}
            .figure .unit{font-size:13px;font-weight:400;color:var(--muted);margin-left:3px}
            .figure .sub{font-size:12px;color:var(--subtle)}
            .HIGH{color:var(--good)}.MEDIUM{color:var(--warn)}.LOW,.INSUFFICIENT{color:var(--bad)}
            section{margin-top:40px}
            h2{font-size:17px;font-weight:600;margin:0 0 12px;padding-bottom:8px;border-bottom:1px solid var(--line)}
            h3{font-size:14px;font-weight:600;margin:0}
            .estimate{padding:16px 0 20px;border-bottom:1px solid var(--line)}
            .estimate:last-of-type{border-bottom:0}
            .estimate-head{display:flex;align-items:center;gap:4px;flex-wrap:wrap}
            .estimate-head .chip{vertical-align:0;margin-left:6px}
            .lede{font-size:15px;margin:6px 0 10px}.lede.muted{color:var(--muted)}
            dl.facts{display:grid;grid-template-columns:max-content 1fr;gap:3px 20px;margin:12px 0 0;font-size:13px}
            dl.facts dt{color:var(--muted)}dl.facts dd{margin:0}
            .notes{margin:12px 0 0;padding:0 0 0 16px;font-size:12.5px;color:var(--muted)}
            .notes li{margin:2px 0}
            .caption{font-size:12px;color:var(--subtle);margin:4px 0 0}
            .aside{font-size:12.5px;color:var(--muted);background:var(--fill);border:1px solid var(--line);
            border-radius:6px;padding:10px 12px;margin:16px 0 0}
            table{border-collapse:collapse;width:100%;font-size:13px}
            th,td{text-align:left;padding:6px 12px 6px 0;border-bottom:1px solid var(--line);vertical-align:top}
            th{color:var(--muted);font-weight:500}
            td.num,th.num{text-align:right}
            .scroll{overflow-x:auto}
            .findings{list-style:none;padding:0;margin:0}
            .findings li{padding:9px 0;border-bottom:1px solid var(--line)}
            .sev{font-size:12px;font-weight:600;margin-right:8px}
            .sev.CRITICAL{color:var(--bad)}.sev.WARNING{color:var(--warn)}.sev.INFO{color:var(--muted)}
            .advice{font-size:12.5px;color:var(--muted);margin-top:2px}
            .stack>div+div{margin-top:14px}
            .method p{color:var(--muted);font-size:13px}
            footer{margin-top:56px;padding-top:12px;border-top:1px solid var(--line);font-size:12px;color:var(--subtle)}
            svg.chart{width:100%;height:auto;display:block;margin-top:4px}
            .chart text{font-family:inherit}
            .chart .grid line{stroke:var(--line)}.chart .tick,.chart .axis-label{fill:var(--subtle);font-size:11px}
            .chart .axis{stroke:var(--line-strong)}.chart .band{fill:var(--plot-soft)}
            .chart .fit{stroke:var(--plot);stroke-width:1.6}.chart .pts circle{fill:var(--fg)}
            .chart .pts2 circle{fill:var(--plot)}.chart .line2{stroke:var(--plot);stroke-width:1.5}
            .chart .bars rect{fill:var(--bar)}
            .chart .ref line{stroke:var(--warn);stroke-dasharray:3 4}.chart .ref text{fill:var(--warn);font-size:11px}
            .chart .crit line{stroke:var(--bad);stroke-dasharray:3 4}.chart .crit text{fill:var(--bad);font-size:11px}
            .chart .mark line{stroke:var(--good)}.chart .mark text{fill:var(--good);font-size:11px}
            @media (max-width:640px){main{padding:20px 16px 56px}h1{font-size:21px}
            .figures{grid-template-columns:1fr 1fr}.figure:nth-child(3){border-left:0}
            .figure:nth-child(n+3){border-top:1px solid var(--line)}}
            """;

    private HtmlReport() {
    }

    static String render(ReportWriter.ReportData d, Messages m, LocalDateTime now) {
        CapacityService.Snapshot s = d.snapshot();
        HardwareInfo hw = d.hardware();
        StringBuilder sb = new StringBuilder(40_000);
        sb.append("<!doctype html><html lang=\"").append(esc(m.locale().getLanguage())).append("\"><head>")
                .append("<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
                .append("<title>").append(esc(m.plain("report.title"))).append("</title><style>").append(CSS)
                .append("</style></head><body><main>");

        sb.append("<header class=\"top\"><span class=\"brand\">TickCapacity</span><span class=\"meta\">")
                .append(esc(m.plain("report.meta", now.format(ReportWriter.SHOWN_TS), d.pluginVersion(),
                        hw.minecraftVersion(), hw.cpuModel()))).append("</span></header>");
        sb.append("<h1>").append(esc(ReportWriter.headline(m, s)));
        ReportWriter.headlineEstimate(s).filter(e -> e.confidence() == Confidence.LOW)
                .ifPresent(e -> sb.append(chip(m, e.confidence(), m.plain("report.rough"))));
        sb.append("</h1>");

        figures(sb, d, m);

        sb.append("<section><h2>").append(esc(m.plain("report.section.capacity"))).append("</h2>");
        estimate(sb, m, d, s.observed(), m.plain("report.estimate.observed"));
        if (s.benchmarkEstimate() != null) {
            estimate(sb, m, d, s.benchmarkEstimate(), m.plain("report.estimate.benchmark") + " · "
                    + s.benchmark().id());
        }
        if (s.calibration() != null) {
            sb.append("<p class=\"aside\">").append(esc(InsightFormatter.calibrationPlain(m, s.calibration())))
                    .append("</p>");
        }
        sb.append("<p class=\"aside\">").append(esc(m.plain("report.capacity-disclaimer"))).append("</p></section>");

        activity(sb, m, s.activity());
        hourly(sb, m, s.hourly(), d.thresholds().comfortableMspt());
        performance(sb, m, d);
        stages(sb, m, s.benchmark());

        sb.append("<section><h2>").append(esc(m.plain("report.section.hardware"))).append("</h2>")
                .append("<div class=\"scroll\"><table>");
        for (String[] row : ReportWriter.hardwareRows(m, hw)) {
            sb.append("<tr><th>").append(esc(row[0])).append("</th><td>").append(esc(row[1])).append("</td></tr>");
        }
        sb.append("</table></div></section>");

        sb.append("<section class=\"method\"><h2>").append(esc(m.plain("report.section.method"))).append("</h2>");
        for (String para : m.plain("report.method").split("\n\n")) {
            sb.append("<p>").append(esc(para.replace("**", "").replace("`", ""))).append("</p>");
        }
        sb.append("</section><footer>").append(esc(m.plain("report.footer", d.pluginVersion())))
                .append("</footer></main></body></html>");
        return sb.toString();
    }

    private static void figures(StringBuilder sb, ReportWriter.ReportData d, Messages m) {
        Optional<CapacityEstimate> best = ReportWriter.headlineEstimate(d.snapshot());
        String unit = m.plain("report.unit.players");
        sb.append("<div class=\"figures\">");
        if (best.isPresent()) {
            CapacityEstimate e = best.get();
            figure(sb, m.plain("html.recommended"), EstimateFormatter.players(e.recommended().point()), unit,
                    range(m, e.recommended().low(), e.recommended().high()), "");
            figure(sb, m.plain("html.limit"), EstimateFormatter.players(e.limit().point()), unit,
                    range(m, e.limit().low(), e.limit().high()), "");
        } else {
            figure(sb, m.plain("html.recommended"), "–", "", m.plain("html.no-estimate"), "");
            figure(sb, m.plain("html.limit"), "–", "", "", "");
        }
        Confidence c = best.map(CapacityEstimate::confidence).orElse(Confidence.INSUFFICIENT);
        figure(sb, m.plain("html.confidence"), m.plain("confidence." + c.name()), "",
                best.map(e -> m.plain("ui.source." + e.source().name())).orElse(""), c.name());
        TickSummary t = d.last60s();
        figure(sb, "TPS", f1(d.tps60s()), "", m.plain("report.fig.now-sub", f1(t.meanMs()), d.playersOnline()), "");
        sb.append("</div>");
    }

    private static void figure(StringBuilder sb, String label, String value, String unit, String sub,
                               String valueClass) {
        sb.append("<div class=\"figure\"><div class=\"label\">").append(esc(label)).append("</div>")
                .append("<div class=\"value ").append(valueClass).append("\">").append(esc(value));
        if (!unit.isEmpty()) {
            sb.append("<span class=\"unit\">").append(esc(unit)).append("</span>");
        }
        sb.append("</div><div class=\"sub\">").append(esc(sub)).append("</div></div>");
    }

    private static String chip(Messages m, Confidence c, String text) {
        return "<span class=\"chip " + c.name() + "\">" + esc(text) + "</span>";
    }

    private static void estimate(StringBuilder sb, Messages m, ReportWriter.ReportData d, CapacityEstimate e,
                                 String title) {
        sb.append("<div class=\"estimate\"><div class=\"estimate-head\"><h3>").append(esc(title))
                .append("</h3>").append(chip(m, e.confidence(),
                        m.plain("html.confidence") + " " + m.plain("confidence." + e.confidence().name())))
                .append("</div>");
        boolean known = e.confidence() != Confidence.INSUFFICIENT && Double.isFinite(e.recommended().point());
        if (known) {
            sb.append("<p class=\"lede\">").append(esc(m.plain("report.estimate.lede",
                    ReportWriter.players(m, e, e.recommended().point()),
                    ReportWriter.players(m, e, e.limit().point()))))
                    .append("</p>");
        } else {
            sb.append("<p class=\"lede muted\">").append(esc(m.plain("report.estimate.insufficient"))).append("</p>");
        }
        if (e.pointsOrEmpty().size() >= 2) {
            sb.append(capacityChart(m, e, d));
            sb.append("<p class=\"caption\">").append(esc(m.plain("html.chart-legend"))).append("</p>");
        }
        sb.append("<dl class=\"facts\">");
        if (known) {
            fact(sb, m.plain("html.recommended"), m.plain("report.fact.range-value",
                    EstimateFormatter.players(e.recommended().point()), EstimateFormatter.players(e.recommended().low()),
                    EstimateFormatter.players(e.recommended().high())));
            fact(sb, m.plain("html.limit"), m.plain("report.fact.range-value",
                    EstimateFormatter.players(e.limit().point()), EstimateFormatter.players(e.limit().low()),
                    EstimateFormatter.players(e.limit().high())));
            fact(sb, m.plain("report.fact.per-player"), m.plain("report.fact.per-player-value",
                    EstimateFormatter.f2(e.perPlayerMs()), EstimateFormatter.f2(e.perPlayerMsStdError())));
            fact(sb, m.plain("report.fact.base"), f1(e.baseMs()) + " ms");
        }
        fact(sb, m.plain("report.fact.data"), m.plain("report.fact.data-value", e.samples(), e.distinctLoadLevels(),
                e.maxObservedPlayers()));
        if (e.provenComfortablePlayers() > 0) {
            fact(sb, m.plain("report.fact.proven"), m.plain("report.fact.proven-value", e.provenComfortablePlayers()));
        }
        sb.append("</dl>");
        List<CapacityEstimate.Note> notes = e.notesOrEmpty().stream().filter(n -> m.has("note." + n.key())).toList();
        if (!notes.isEmpty()) {
            sb.append("<ul class=\"notes\">");
            for (CapacityEstimate.Note n : notes) {
                sb.append("<li>").append(esc(m.plain("note." + n.key(), EstimateFormatter.noteValue(n))
                        .replaceFirst("^•\\s*", ""))).append("</li>");
            }
            sb.append("</ul>");
        }
        sb.append("</div>");
    }

    private static void fact(StringBuilder sb, String term, String value) {
        sb.append("<dt>").append(esc(term)).append("</dt><dd>").append(esc(value)).append("</dd>");
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
        SvgChart chart = new SvgChart(m.plain("html.axis.players"), m.plain("html.axis.mspt"), 0,
                SvgChart.niceMax(xMax), 0, SvgChart.niceMax(yMax));
        if (!curve.isEmpty()) {
            double[] cx = curve.stream().mapToDouble(CapacityEstimate.CurvePoint::players).toArray();
            chart.band(cx, curve.stream().mapToDouble(CapacityEstimate.CurvePoint::lowerMs).toArray(),
                    curve.stream().mapToDouble(CapacityEstimate.CurvePoint::upperMs).toArray(), "band");
            chart.line(cx, curve.stream().mapToDouble(CapacityEstimate.CurvePoint::meanMs).toArray(), "fit");
        }
        String[] tips = points.stream().map(p -> f1(p.players()) + " → " + f1(p.meanMs()) + " ms (p95 "
                + f1(p.p95Ms()) + ", n=" + p.samples() + ")").toArray(String[]::new);
        chart.points(points.stream().mapToDouble(CapacityEstimate.LoadPoint::players).toArray(),
                points.stream().mapToDouble(CapacityEstimate.LoadPoint::meanMs).toArray(),
                points.stream().mapToDouble(CapacityEstimate.LoadPoint::samples).toArray(), "pts", tips);
        chart.hline(d.thresholds().comfortableMspt(), m.plain("html.comfortable-line"), "ref");
        chart.hline(limitMs, m.plain("html.limit-line"), "crit");
        if (e.confidence() != Confidence.INSUFFICIENT) {
            chart.vline(e.recommended().point(), m.plain("html.recommended") + " "
                    + EstimateFormatter.players(e.recommended().point()), "mark");
        }
        return chart.render(m.plain("html.axis.players") + " / " + m.plain("html.axis.mspt"));
    }

    private static void activity(StringBuilder sb, Messages m, ActivityAnalyzer.Result a) {
        sb.append("<section><h2>").append(esc(m.plain("activity.header"))).append("</h2>");
        if (!a.available()) {
            sb.append("<p class=\"lede muted\">").append(esc(m.plain(a.reasonKey(), a.samples(),
                    ActivityAnalyzer.MIN_SAMPLES))).append("</p></section>");
            return;
        }
        sb.append("<p>").append(esc(m.plain("activity.model", EstimateFormatter.f2(a.perPlayerMs()),
                EstimateFormatter.f2(a.perChunkPerSecondMs()), EstimateFormatter.f2(a.typicalRatePerPlayer()),
                EstimateFormatter.f2(a.heavyRatePerPlayer())))).append("</p>");
        sb.append("<table><tr><th>").append(esc(m.plain("report.scenario.style"))).append("</th><th class=\"num\">")
                .append(esc(m.plain("report.scenario.rate"))).append("</th><th class=\"num\">")
                .append(esc(m.plain("html.recommended"))).append("</th><th class=\"num\">")
                .append(esc(m.plain("html.limit"))).append("</th></tr>");
        for (ActivityAnalyzer.Scenario sc : a.scenarios()) {
            sb.append("<tr><td>").append(esc(m.plain("report.scenario." + sc.key()))).append("</td><td class=\"num\">")
                    .append(EstimateFormatter.f2(sc.chunksPerSecondPerPlayer())).append("</td><td class=\"num\">")
                    .append(EstimateFormatter.players(sc.recommended())).append("</td><td class=\"num\">")
                    .append(EstimateFormatter.players(sc.limit())).append("</td></tr>");
        }
        sb.append("</table><p class=\"caption\">").append(esc(m.plain("activity.caveat"))).append("</p></section>");
    }

    private static void hourly(StringBuilder sb, Messages m, HourlyProfile p, double comfortable) {
        sb.append("<section><h2>").append(esc(m.plain("report.section.hourly"))).append("</h2>");
        if (p.peakPlayers().isEmpty()) {
            sb.append("<p class=\"lede muted\">").append(esc(m.plain("peak.no-data"))).append("</p></section>");
            return;
        }
        double[] hours = new double[24];
        double[] players = new double[24];
        double[] mspt = new double[24];
        String[] tips = new String[24];
        double maxPlayers = 1;
        double maxMspt = 1;
        for (HourlyProfile.Hour h : p.hours()) {
            int i = h.hour();
            hours[i] = i;
            players[i] = h.samples() > 0 ? h.avgPlayers() : Double.NaN;
            mspt[i] = h.samples() > 0 ? h.medianMspt() : Double.NaN;
            tips[i] = i + ":00  " + f1(h.avgPlayers()) + " / " + h.maxPlayers() + "  MSPT " + f1(h.medianMspt());
            if (h.samples() > 0) {
                maxPlayers = Math.max(maxPlayers, h.avgPlayers());
                maxMspt = Math.max(maxMspt, h.medianMspt());
            }
        }
        sb.append("<div class=\"stack\"><div>");
        sb.append(new SvgChart(m.plain("html.axis.hour"), m.plain("html.axis.avg-players"), -0.5, 23.5, 0,
                SvgChart.niceMax(maxPlayers)).bars(hours, players, 1, "bars").render(m.plain("html.axis.avg-players")));
        sb.append("</div><div>");
        sb.append(new SvgChart(m.plain("html.axis.hour"), m.plain("html.axis.mspt"), -0.5, 23.5, 0,
                SvgChart.niceMax(Math.max(maxMspt, comfortable))).line(hours, mspt, "line2")
                .points(hours, mspt, new double[24], "pts2", tips)
                .hline(comfortable, m.plain("html.comfortable-line"), "ref").render(m.plain("html.axis.mspt")));
        sb.append("</div></div>");
        p.peakPlayers().ifPresent(h -> sb.append("<p class=\"caption\">").append(esc(m.plain("peak.peak", h.hour(),
                f1(h.avgPlayers()), h.maxPlayers(), f1(h.medianMspt())))).append(" ").append(p.quietest()
                .map(q -> esc(m.plain("peak.quiet", q.hour(), f1(q.avgPlayers())))).orElse("")).append("</p>"));
        sb.append("</section>");
    }

    private static void performance(StringBuilder sb, Messages m, ReportWriter.ReportData d) {
        TickSummary t = d.last60s();
        sb.append("<section><h2>").append(esc(m.plain("report.section.current"))).append("</h2>")
                .append("<div class=\"scroll\"><table><tr>");
        for (String h : List.of("TPS", m.plain("report.col.mspt-mean"), "p50", "p95", "p99", m.plain("report.col.max"),
                m.plain("report.players"))) {
            sb.append("<th class=\"num\">").append(esc(h)).append("</th>");
        }
        sb.append("</tr><tr>");
        for (String v : List.of(f1(d.tps60s()), f1(t.meanMs()), f1(t.p50Ms()), f1(t.p95Ms()), f1(t.p99Ms()),
                f1(t.maxMs()), Integer.toString(d.playersOnline()))) {
            sb.append("<td class=\"num\">").append(esc(v)).append("</td>");
        }
        sb.append("</tr></table></div>");
        sb.append("<h3 style=\"margin-top:26px\">").append(esc(m.plain("report.section.bottlenecks")))
                .append("</h3><ul class=\"findings\">");
        for (Finding f : d.findings()) {
            sb.append("<li><span class=\"sev ").append(f.severity().name()).append("\">")
                    .append(esc(m.plain("severity." + f.severity().name()))).append("</span>")
                    .append(esc(m.plain("finding." + f.key(), f.args().toArray())));
            if (m.has("advice." + f.key())) {
                sb.append("<div class=\"advice\">").append(esc(m.plain("advice." + f.key(), f.args().toArray())))
                        .append("</div>");
            }
            sb.append("</li>");
        }
        if (d.sparkPresent()) {
            sb.append("<li>").append(esc(m.plain("analyze.spark-hint"))).append("</li>");
        }
        sb.append("</ul></section>");
    }

    private static void stages(StringBuilder sb, Messages m, BenchmarkResult b) {
        if (b == null || b.stages().isEmpty()) {
            return;
        }
        sb.append("<section><h2>").append(esc(m.plain("report.section.stages", b.id()))).append("</h2>")
                .append("<div class=\"scroll\"><table><tr>");
        for (String h : ReportWriter.stageHeaders(m)) {
            sb.append("<th class=\"num\">").append(esc(h)).append("</th>");
        }
        sb.append("</tr>");
        for (StageResult st : b.stages()) {
            sb.append("<tr>");
            for (String v : ReportWriter.stageRow(st)) {
                sb.append("<td class=\"num\">").append(esc(v)).append("</td>");
            }
            sb.append("</tr>");
        }
        sb.append("</table></div><p class=\"caption\">").append(esc(ReportWriter.benchParams(m, b)))
                .append("</p></section>");
    }

    private static String range(Messages m, double low, double high) {
        String lo = EstimateFormatter.players(low);
        String hi = EstimateFormatter.players(high);
        return lo.equals(hi) ? "" : m.plain("report.fig.range", lo, hi);
    }

    private static String esc(String s) {
        return Html.escape(s);
    }

    private static String f1(double v) {
        return EstimateFormatter.f1(v);
    }
}

package sh.aki.tickcapacity.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small SVG chart builder. Colours come from CSS classes so charts follow the page theme. */
final class SvgChart {

    private static final int W = 640;
    private static final int H = 300;
    private static final int LEFT = 52;
    private static final int RIGHT = 16;
    private static final int TOP = 16;
    private static final int BOTTOM = 40;

    private final String xLabel;
    private final String yLabel;
    private final double xMin;
    private final double xMax;
    private final double yMin;
    private final double yMax;
    private final List<String> layers = new ArrayList<>();

    SvgChart(String xLabel, String yLabel, double xMin, double xMax, double yMin, double yMax) {
        this.xLabel = xLabel;
        this.yLabel = yLabel;
        this.xMin = xMin;
        this.xMax = xMax > xMin ? xMax : xMin + 1;
        this.yMin = yMin;
        this.yMax = yMax > yMin ? yMax : yMin + 1;
    }

    private double px(double x) {
        return LEFT + (x - xMin) / (xMax - xMin) * (W - LEFT - RIGHT);
    }

    private double py(double y) {
        return H - BOTTOM - (y - yMin) / (yMax - yMin) * (H - TOP - BOTTOM);
    }

    private double clampY(double y) {
        return Math.max(yMin, Math.min(yMax, y));
    }

    SvgChart band(double[] x, double[] lower, double[] upper, String cssClass) {
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < x.length; i++) {
            d.append(i == 0 ? "M" : "L").append(f(px(x[i]))).append(',').append(f(py(clampY(upper[i]))));
        }
        for (int i = x.length - 1; i >= 0; i--) {
            d.append('L').append(f(px(x[i]))).append(',').append(f(py(clampY(lower[i]))));
        }
        d.append('Z');
        layers.add("<path class=\"" + cssClass + "\" d=\"" + d + "\"/>");
        return this;
    }

    SvgChart line(double[] x, double[] y, String cssClass) {
        StringBuilder d = new StringBuilder();
        boolean pen = false;
        for (int i = 0; i < x.length; i++) {
            if (Double.isNaN(y[i])) {
                pen = false;
                continue;
            }
            d.append(pen ? "L" : "M").append(f(px(x[i]))).append(',').append(f(py(clampY(y[i]))));
            pen = true;
        }
        layers.add("<path class=\"" + cssClass + "\" fill=\"none\" d=\"" + d + "\"/>");
        return this;
    }

    /** Radius grows with the weight; the tooltip is a native SVG title. */
    SvgChart points(double[] x, double[] y, double[] weight, String cssClass, String[] tooltips) {
        double maxW = 1;
        for (double w : weight) {
            maxW = Math.max(maxW, w);
        }
        StringBuilder sb = new StringBuilder("<g class=\"" + cssClass + "\">");
        for (int i = 0; i < x.length; i++) {
            if (Double.isNaN(y[i])) {
                continue;
            }
            double r = 2.5 + 2.5 * Math.sqrt(weight[i] / maxW);
            sb.append("<circle cx=\"").append(f(px(x[i]))).append("\" cy=\"").append(f(py(clampY(y[i]))))
                    .append("\" r=\"").append(f(r)).append("\"><title>").append(Html.escape(tooltips[i]))
                    .append("</title></circle>");
        }
        layers.add(sb.append("</g>").toString());
        return this;
    }

    SvgChart bars(double[] x, double[] y, double width, String cssClass) {
        StringBuilder sb = new StringBuilder("<g class=\"" + cssClass + "\">");
        double bw = Math.abs(px(xMin + width) - px(xMin)) * 0.8;
        for (int i = 0; i < x.length; i++) {
            if (Double.isNaN(y[i]) || y[i] <= yMin) {
                continue;
            }
            double top = py(clampY(y[i]));
            sb.append("<rect x=\"").append(f(px(x[i]) - bw / 2)).append("\" y=\"").append(f(top))
                    .append("\" width=\"").append(f(bw)).append("\" height=\"").append(f(py(yMin) - top))
                    .append("\" rx=\"2\"/>");
        }
        layers.add(sb.append("</g>").toString());
        return this;
    }

    SvgChart hline(double y, String label, String cssClass) {
        if (y < yMin || y > yMax) {
            return this;
        }
        double yy = py(y);
        layers.add("<g class=\"" + cssClass + "\"><line x1=\"" + LEFT + "\" x2=\"" + (W - RIGHT) + "\" y1=\""
                + f(yy) + "\" y2=\"" + f(yy) + "\"/><text x=\"" + (W - RIGHT - 4) + "\" y=\"" + f(yy - 4)
                + "\" text-anchor=\"end\">" + Html.escape(label) + "</text></g>");
        return this;
    }

    SvgChart vline(double x, String label, String cssClass) {
        if (!Double.isFinite(x) || x < xMin || x > xMax) {
            return this;
        }
        double xx = px(x);
        layers.add("<g class=\"" + cssClass + "\"><line x1=\"" + f(xx) + "\" x2=\"" + f(xx) + "\" y1=\"" + TOP
                + "\" y2=\"" + (H - BOTTOM) + "\"/><text x=\"" + f(xx + 4) + "\" y=\"" + (TOP + 12) + "\">"
                + Html.escape(label) + "</text></g>");
        return this;
    }

    String render(String ariaLabel) {
        StringBuilder sb = new StringBuilder();
        sb.append("<svg class=\"chart\" viewBox=\"0 0 ").append(W).append(' ').append(H)
                .append("\" role=\"img\" aria-label=\"").append(Html.escape(ariaLabel)).append("\">");
        sb.append("<g class=\"grid\">");
        for (double t : ticks(yMin, yMax)) {
            double y = py(t);
            sb.append("<line x1=\"").append(LEFT).append("\" x2=\"").append(W - RIGHT).append("\" y1=\"").append(f(y))
                    .append("\" y2=\"").append(f(y)).append("\"/>");
            sb.append("<text class=\"tick\" x=\"").append(LEFT - 6).append("\" y=\"").append(f(y + 4))
                    .append("\" text-anchor=\"end\">").append(label(t)).append("</text>");
        }
        for (double t : ticks(xMin, xMax)) {
            double x = px(t);
            sb.append("<text class=\"tick\" x=\"").append(f(x)).append("\" y=\"").append(H - BOTTOM + 16)
                    .append("\" text-anchor=\"middle\">").append(label(t)).append("</text>");
        }
        sb.append("</g>");
        sb.append("<line class=\"axis\" x1=\"").append(LEFT).append("\" x2=\"").append(W - RIGHT).append("\" y1=\"")
                .append(H - BOTTOM).append("\" y2=\"").append(H - BOTTOM).append("\"/>");
        layers.forEach(sb::append);
        sb.append("<text class=\"axis-label\" x=\"").append((LEFT + W - RIGHT) / 2).append("\" y=\"").append(H - 6)
                .append("\" text-anchor=\"middle\">").append(Html.escape(xLabel)).append("</text>");
        sb.append("<text class=\"axis-label\" transform=\"translate(14,").append((TOP + H - BOTTOM) / 2)
                .append(") rotate(-90)\" text-anchor=\"middle\">").append(Html.escape(yLabel)).append("</text>");
        return sb.append("</svg>").toString();
    }

    /** About five ticks at 1, 2 or 5 times a power of ten. */
    static List<Double> ticks(double min, double max) {
        double range = max - min;
        double rough = range / 5;
        double mag = Math.pow(10, Math.floor(Math.log10(rough)));
        double norm = rough / mag;
        double step = (norm < 1.5 ? 1 : norm < 3 ? 2 : norm < 7 ? 5 : 10) * mag;
        List<Double> out = new ArrayList<>();
        for (double t = Math.ceil(min / step) * step; t <= max + step * 1e-9; t += step) {
            out.add(Math.abs(t) < step * 1e-9 ? 0 : t);
        }
        return out;
    }

    static double niceMax(double v) {
        if (!(v > 0) || !Double.isFinite(v)) {
            return 1;
        }
        List<Double> t = ticks(0, v * 1.1);
        return t.getLast() < v * 1.05 ? t.getLast() + (t.size() > 1 ? t.get(1) - t.get(0) : 1) : t.getLast();
    }

    private static String label(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}

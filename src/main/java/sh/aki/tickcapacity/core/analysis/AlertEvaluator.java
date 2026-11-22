package sh.aki.tickcapacity.core.analysis;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Decides when to alert. Feed it one observation per second. MSPT alerts need the condition to hold for
 * {@code sustainMs} (recovery below 90% of the threshold); capacity alerts re-arm once the count drops back.
 * Each type has its own cooldown.
 */
public final class AlertEvaluator {

    public enum Type { MSPT_HIGH, MSPT_RECOVERED, OVER_RECOMMENDED, OVER_LIMIT }

    public record Alert(Type type, double value, double threshold) {
    }

    private final double msptThreshold;
    private final long sustainMs;
    private final long cooldownMs;

    private long highSince = -1;
    private long lowSince = -1;
    private boolean msptActive;
    private boolean overRecommended;
    private boolean overLimit;
    private final Map<Type, Long> lastSent = new EnumMap<>(Type.class);

    public AlertEvaluator(double msptThreshold, long sustainMs, long cooldownMs) {
        this.msptThreshold = msptThreshold;
        this.sustainMs = sustainMs;
        this.cooldownMs = cooldownMs;
    }

    /** Pass NaN for estimates that are unknown or not trusted. */
    public List<Alert> update(long nowMs, double rollingMspt, int online, double recommended, double limit) {
        List<Alert> out = new ArrayList<>();
        if (!Double.isNaN(rollingMspt)) {
            if (rollingMspt >= msptThreshold) {
                lowSince = -1;
                if (highSince < 0) {
                    highSince = nowMs;
                }
                if (!msptActive && nowMs - highSince >= sustainMs && cooledDown(Type.MSPT_HIGH, nowMs)) {
                    msptActive = true;
                    send(out, new Alert(Type.MSPT_HIGH, rollingMspt, msptThreshold), nowMs);
                }
            } else {
                highSince = -1;
                if (msptActive && rollingMspt < msptThreshold * 0.9) {
                    if (lowSince < 0) {
                        lowSince = nowMs;
                    }
                    if (nowMs - lowSince >= sustainMs) {
                        msptActive = false;
                        lowSince = -1;
                        send(out, new Alert(Type.MSPT_RECOVERED, rollingMspt, msptThreshold), nowMs);
                    }
                } else {
                    lowSince = -1;
                }
            }
        }

        if (Double.isFinite(limit) && online >= Math.max(1, Math.floor(limit))) {
            if (!overLimit && cooledDown(Type.OVER_LIMIT, nowMs)) {
                overLimit = true;
                send(out, new Alert(Type.OVER_LIMIT, online, Math.floor(limit)), nowMs);
            }
        } else {
            overLimit = false;
        }
        if (Double.isFinite(recommended) && online > Math.floor(recommended) && !overLimit) {
            if (!overRecommended && cooledDown(Type.OVER_RECOMMENDED, nowMs)) {
                overRecommended = true;
                send(out, new Alert(Type.OVER_RECOMMENDED, online, Math.floor(recommended)), nowMs);
            }
        } else if (!Double.isFinite(recommended) || online <= Math.floor(recommended)) {
            overRecommended = false;
        }
        return out;
    }

    private boolean cooledDown(Type type, long nowMs) {
        Long last = lastSent.get(type);
        return last == null || nowMs - last >= cooldownMs;
    }

    private void send(List<Alert> out, Alert alert, long nowMs) {
        lastSent.put(alert.type(), nowMs);
        out.add(alert);
    }
}

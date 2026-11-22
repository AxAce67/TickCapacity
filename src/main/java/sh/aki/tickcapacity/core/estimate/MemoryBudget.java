package sh.aki.tickcapacity.core.estimate;

import java.util.List;

/** Estimates how many more virtual players fit in the heap, from the heap measured at each stage. */
public final class MemoryBudget {

    /** A noisy early reading must not make players look free. */
    static final double MIN_MB_PER_PLAYER = 5;

    public record Point(int virtualPlayers, double heapMb) {
    }

    private MemoryBudget() {
    }

    /** Largest per-player increase over the baseline seen so far. */
    public static double mbPerPlayer(List<Point> points) {
        if (points.size() < 2) {
            return Double.NaN;
        }
        Point base = points.getFirst();
        double worst = Double.NaN;
        for (Point p : points.subList(1, points.size())) {
            if (p.virtualPlayers() > base.virtualPlayers()) {
                double perPlayer = (p.heapMb() - base.heapMb()) / (p.virtualPlayers() - base.virtualPlayers());
                worst = Double.isNaN(worst) ? perPlayer : Math.max(worst, perPlayer);
            }
        }
        return Double.isNaN(worst) ? Double.NaN : Math.max(MIN_MB_PER_PLAYER, worst);
    }

    /** @return highest count that fits in {@code budgetRatio} of the heap; MAX_VALUE while unknown */
    public static int maxAffordable(List<Point> points, int current, double heapNowMb, double maxHeapMb,
                                    double budgetRatio) {
        double perPlayer = mbPerPlayer(points);
        if (Double.isNaN(perPlayer) || maxHeapMb <= 0) {
            return Integer.MAX_VALUE;
        }
        double headroom = maxHeapMb * budgetRatio - heapNowMb;
        if (headroom <= 0) {
            return current;
        }
        return current + (int) Math.floor(headroom / perPlayer);
    }
}

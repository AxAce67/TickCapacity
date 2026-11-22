package sh.aki.tickcapacity.core.estimate;

import java.util.List;

/**
 * Picks the size of the next stage. Each step covers about a third of the predicted distance to the MSPT
 * limit: large steps while there is headroom, small ones near the limit. A step never more than doubles the
 * count, which limits the damage a single noisy stage can do.
 */
public final class StepPlanner {

    private StepPlanner() {
    }

    /** @param fixedStep configured step, or 0 for adaptive */
    public static int next(List<StageResult> stagesThisRun, int current, int max, int fixedStep, double limitMs) {
        if (fixedStep > 0) {
            return Math.min(max, current + fixedStep);
        }
        int minStep = 1;
        int firstStep = Math.max(2, (int) Math.round(max / 20.0));
        if (current == 0 || stagesThisRun.size() < 2) {
            return Math.min(max, current + firstStep);
        }
        double base = stagesThisRun.getFirst().ticks().meanMs();
        double last = stagesThisRun.getLast().ticks().meanMs();
        double perPlayer = (last - base) / current;
        int step;
        if (!(perPlayer > 0) || Double.isNaN(last)) {
            step = Math.max(firstStep, current); // no measurable cost yet: double
        } else {
            double toLimit = Math.max(0, limitMs - last) / perPlayer;
            step = (int) Math.ceil(toLimit / 3);
            step = Math.max(minStep, step);
        }
        step = Math.min(step, Math.max(firstStep, current)); // at most double
        return Math.min(max, current + step);
    }
}

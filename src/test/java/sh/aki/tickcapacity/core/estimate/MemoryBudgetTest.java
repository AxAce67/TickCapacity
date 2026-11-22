package sh.aki.tickcapacity.core.estimate;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoryBudgetTest {

    @Test
    void unknownUntilThereIsData() {
        assertEquals(Integer.MAX_VALUE, MemoryBudget.maxAffordable(List.of(new MemoryBudget.Point(0, 500)), 0, 500,
                2048, 0.85));
    }

    @Test
    void plansWithinTheBudget() {
        // Measured on a 2 GB server: about 60 MB per virtual player.
        List<MemoryBudget.Point> pts = List.of(new MemoryBudget.Point(0, 500), new MemoryBudget.Point(4, 740),
                new MemoryBudget.Point(8, 980));
        assertEquals(60, MemoryBudget.mbPerPlayer(pts), 1e-9);
        int max = MemoryBudget.maxAffordable(pts, 8, 980, 2048, 0.85);
        // 0.85 * 2048 = 1740.8; headroom 760.8 MB / 60 = 12 more
        assertEquals(20, max);
    }

    @Test
    void usesTheMostExpensiveLevel() {
        List<MemoryBudget.Point> pts = List.of(new MemoryBudget.Point(0, 500), new MemoryBudget.Point(2, 600),
                new MemoryBudget.Point(4, 900));
        assertEquals(100, MemoryBudget.mbPerPlayer(pts), 1e-9);
    }

    @Test
    void noHeadroomMeansNoMorePlayers() {
        List<MemoryBudget.Point> pts = List.of(new MemoryBudget.Point(0, 500), new MemoryBudget.Point(10, 1800));
        assertEquals(10, MemoryBudget.maxAffordable(pts, 10, 1800, 2048, 0.85));
    }

    @Test
    void noisyFreeReadingIsFloored() {
        List<MemoryBudget.Point> pts = List.of(new MemoryBudget.Point(0, 900), new MemoryBudget.Point(4, 850));
        assertTrue(MemoryBudget.mbPerPlayer(pts) >= MemoryBudget.MIN_MB_PER_PLAYER);
    }
}

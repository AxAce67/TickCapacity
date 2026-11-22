package sh.aki.tickcapacity.core.stats;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatsTest {

    @Test
    void percentileMatchesLinearInterpolationDefinition() {
        double[] v = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        assertEquals(1, Stats.percentile(v, v.length, 0), 1e-12);
        assertEquals(10, Stats.percentile(v, v.length, 1), 1e-12);
        assertEquals(5.5, Stats.percentile(v, v.length, 0.5), 1e-12);
        assertEquals(9.55, Stats.percentile(v, v.length, 0.95), 1e-12); // numpy.percentile(v, 95)
    }

    @Test
    void percentileOfEmptyIsNaN() {
        assertTrue(Double.isNaN(Stats.percentile(new double[0], 0, 0.5)));
    }

    @Test
    void ringBufferKeepsMostRecentValuesInOrder() {
        DoubleRingBuffer rb = new DoubleRingBuffer(3);
        rb.add(1);
        rb.add(2);
        assertArrayEquals(new double[]{1, 2}, rb.latest(10));
        rb.add(3);
        rb.add(4);
        assertEquals(3, rb.size());
        assertArrayEquals(new double[]{2, 3, 4}, rb.latest(3));
        assertArrayEquals(new double[]{3, 4}, rb.latest(2));
    }

    @Test
    void doubleListGrows() {
        DoubleList list = new DoubleList(4);
        for (int i = 0; i < 100; i++) {
            list.add(i);
        }
        assertEquals(100, list.size());
        assertEquals(99, list.get(99));
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(100));
    }
}

package sh.aki.tickcapacity.core.stats;

public final class DoubleRingBuffer {

    private final double[] data;
    private int next;
    private int size;

    public DoubleRingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.data = new double[capacity];
    }

    public void add(double value) {
        data[next] = value;
        next = (next + 1) % data.length;
        if (size < data.length) {
            size++;
        }
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return data.length;
    }

    public void clear() {
        next = 0;
        size = 0;
    }

    /** The most recent {@code count} values, oldest first. */
    public double[] latest(int count) {
        int n = Math.min(count, size);
        double[] out = new double[n];
        int start = Math.floorMod(next - n, data.length);
        for (int i = 0; i < n; i++) {
            out[i] = data[(start + i) % data.length];
        }
        return out;
    }
}

package sh.aki.tickcapacity.core.stats;

import java.util.Arrays;

/** Growable double array; avoids boxing on the per-tick path. */
public final class DoubleList {

    private double[] data;
    private int size;

    public DoubleList() {
        this(256);
    }

    public DoubleList(int initialCapacity) {
        this.data = new double[Math.max(4, initialCapacity)];
    }

    public void add(double value) {
        if (size == data.length) {
            data = Arrays.copyOf(data, data.length * 2);
        }
        data[size++] = value;
    }

    public int size() {
        return size;
    }

    public double get(int index) {
        if (index >= size) {
            throw new IndexOutOfBoundsException(index);
        }
        return data[index];
    }

    public double[] toArray() {
        return Arrays.copyOf(data, size);
    }

    public void clear() {
        size = 0;
    }
}

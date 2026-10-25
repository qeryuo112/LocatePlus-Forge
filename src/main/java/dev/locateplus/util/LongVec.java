/*
 * Locate Plus
 * Copyright (C) 2026 forest_mask
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package dev.locateplus.util;

import java.util.Arrays;

/** Growable primitive long list for packed {@code BlockPos} values. */
public final class LongVec {

    private long[] data;
    private int size;

    public LongVec() {
        this(16);
    }

    public LongVec(int initialCapacity) {
        this.data = new long[Math.max(4, initialCapacity)];
    }

    public void add(long value) {
        if (size == data.length) {
            grow();
        }
        data[size++] = value;
    }

    private void grow() {
        int next = data.length + (data.length >> 1) + 8;
        if (next < 0) {
            next = Integer.MAX_VALUE - 8;
        }
        data = Arrays.copyOf(data, next);
    }

    public long get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index " + index + " size " + size);
        }
        return data[index];
    }

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** Backing array, valid only for indices {@code [0, size())}. */
    public long[] rawData() {
        return data;
    }

    /** Release excess capacity once a scan has finished collecting. */
    public void trim() {
        if (size != data.length) {
            data = Arrays.copyOf(data, size);
        }
    }
}

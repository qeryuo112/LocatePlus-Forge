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
package dev.locateplus.model;

import dev.locateplus.core.LPConfig;

/** Shared cap on how many individual coordinates one export may retain. */
public final class PositionBudget {

    private final long limit;
    private long used;
    private boolean exhausted;

    public PositionBudget() {
        this(LPConfig.get().maxExportPositions());
    }

    public PositionBudget(long limit) {
        this.limit = limit;
    }

    /**
     * Reserve room for one more coordinate.
     *
     * @return {@code false} once the budget is spent, after which callers stop storing positions
     *         but keep counting
     */
    public boolean claim() {
        if (used >= limit) {
            exhausted = true;
            return false;
        }
        used++;
        return true;
    }

    public boolean exhausted() {
        return exhausted;
    }

    public long used() {
        return used;
    }

    public long limit() {
        return limit;
    }
}

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
import dev.locateplus.scan.BlockClass;
import dev.locateplus.util.LongVec;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Running totals for one block type. */
public final class BlockTally {

    private final ResourceLocation id;
    private long count;

    private BlockPos nearest;
    private double nearestDistanceSq = Double.MAX_VALUE;

    /** Every matching position, kept only when an export was requested and the type stays small. */
    private LongVec positions;

    /** Set once a type grows past the point where listing its positions would be useful. */
    private boolean classChecked;
    private final PositionBudget budget;
    private boolean truncated;

    public BlockTally(ResourceLocation id, boolean collectPositions, PositionBudget budget) {
        this.id = id;
        this.positions = collectPositions ? new LongVec(64) : null;
        this.budget = budget;
    }

    /**
     *
     * @param packedPos {@code BlockPos.asLong()} of the match, passed packed so the hot path
     *                  never allocates a {@code BlockPos}
     */
    public void record(long packedPos, double distanceSq) {
        count++;
        if (distanceSq < nearestDistanceSq) {
            nearestDistanceSq = distanceSq;
            nearest = BlockPos.of(packedPos);
        }
        if (positions == null) {
            return;
        }

        if (!classChecked) {
            classChecked = true;
            if (!LPConfig.get().exportsGroup(BlockClass.category(id))) {
                positions = null;
                    return;
            }
        }

        if (budget != null && !budget.claim()) {
            truncated = true;
        } else {
            positions.add(packedPos);
        }
    }

    public ResourceLocation id() {
        return id;
    }

    public long count() {
        return count;
    }

    public BlockPos nearest() {
        return nearest;
    }

    public double nearestDistance() {
        return nearestDistanceSq == Double.MAX_VALUE ? Double.MAX_VALUE : Math.sqrt(nearestDistanceSq);
    }

    public LongVec positions() {
        return positions;
    }

    public boolean truncated() {
        return truncated;
    }
}

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
package dev.locateplus.teleport;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * A safe landing position plus how far it drifted from what was requested.
 *
 * The offset drives the "4 blocks away" feedback after a teleport.
 */
public final class SafeSpot {

    private final Vec3 position;
    private final BlockPos requested;
    private final int verticalOffset;
    private final int distance;
    private final boolean checked;

    public SafeSpot(Vec3 position, BlockPos requested) {
        this(position, requested, true);
    }

    private SafeSpot(Vec3 position, BlockPos requested, boolean checked) {
        this.position = position;
        this.requested = requested;
        this.checked = checked;
        BlockPos landed = BlockPos.containing(position);
        this.verticalOffset = landed.getY() - requested.getY();
        int dx = landed.getX() - requested.getX();
        int dy = this.verticalOffset;
        int dz = landed.getZ() - requested.getZ();
        this.distance = (int) Math.round(
                Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz));
    }

    /**
     * A landing that was never checked: the exact position asked for, used when the search found
     * nowhere safe and the teleport goes ahead regardless.
     */
    public static SafeSpot unchecked(Vec3 position, BlockPos requested) {
        return new SafeSpot(position, requested, false);
    }

    /** Exact position to teleport to: block centre on X/Z, feet on the floor. */
    public Vec3 position() {
        return position;
    }

    /** Whether this spot came from the safety search rather than being taken as given. */
    public boolean isChecked() {
        return checked;
    }

    public BlockPos blockPos() {
        return BlockPos.containing(position);
    }

    public BlockPos requested() {
        return requested;
    }

    /** Positive when the safe spot is above what was asked for. */
    public int verticalOffset() {
        return verticalOffset;
    }

    /** Straight line distance from the landing to the spot that was asked for, in blocks. */
    public int distance() {
        return distance;
    }

    public boolean isExact() {
        return distance == 0;
    }

    /**
     * One-line landing summary.
     *
     * Reads as {@code "12 -60 12. You are on the target."} when the spot is the target itself,
     * or {@code "12 -56 12. The spot you asked for is 4 blocks away."} when it landed short.
     */
    public String describeLanding() {
        String where = blockPos().getX() + " " + blockPos().getY() + " " + blockPos().getZ();
        return where + ". " + describeOffset();
    }

    /** How far the landing ended up from the position that was asked for. */
    public String describeOffset() {
        if (isExact()) {
            return "You are on the target.";
        }
        String away = distance + (distance == 1 ? " block away." : " blocks away.");
        return "The spot you asked for is " + away;
    }
}

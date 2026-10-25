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

import dev.locateplus.core.LPConfig;

/**
 * A radius plus the unit it was given in.
 *
 * The unit is a separate literal in the command tree rather than a suffix on the number.
 */
public final class Radius {

    /** {@link #LOADED} is not a size at all; it means every chunk the server has in memory. */
    public enum Unit { CHUNKS, BLOCKS, LOADED }

    private final int chunks;
    private final int blocks;
    private final Unit typedUnit;

    private Radius(int chunks, int blocks, Unit typedUnit) {
        this.chunks = chunks;
        this.blocks = blocks;
        this.typedUnit = typedUnit;
    }

    public int chunks() {
        return chunks;
    }

    public int blocks() {
        return blocks;
    }

    /** The unit the player actually typed, before any conversion. */
    public Unit typedUnit() {
        return typedUnit;
    }

    /**
     * A radius given in chunks, reaching {@code chunks} chunks out in every direction.
     *
     * The block equivalent is the outer edge of that reach, so 4 chunks is 64 blocks.
     */
    public static Radius ofChunks(int chunks) {
        return new Radius(chunks, chunks * 16, Unit.CHUNKS);
    }

    public static Radius ofBlocks(int blocks) {
        return new Radius(chunksFor(blocks), blocks, Unit.BLOCKS);
    }

    /**
     * Every chunk currently in memory, which is what a command with no radius covers.
     *
     * Carries no number because there is no meaningful one: the area is whatever the server happens
     * to be holding, which changes as players move and log in.
     */
    public static Radius ofLoaded() {
        return new Radius(0, 0, Unit.LOADED);
    }

    /** Whether this stands for the loaded area rather than a measured distance. */
    public boolean isLoadedArea() {
        return typedUnit == Unit.LOADED;
    }

    /** Round up so nothing inside the requested block radius is skipped. */
    private static int chunksFor(int blocks) {
        return Math.max(1, (blocks + 15) / 16);
    }

    /** True when this radius reaches no further than the chunk the player is standing in. */
    public boolean isSingleChunk() {
        return typedUnit == Unit.CHUNKS && chunks == 0;
    }

    /** {@code "4 chunks (64 blocks)"}, chunk-first, for /lp analyze. */
    public String describe() {
        return chunks + (chunks == 1 ? " chunk (" : " chunks (")
                + blocks + (blocks == 1 ? " block)" : " blocks)");
    }


    // ---- limits -------------------------------------------------------------------------------

    /** The configured chunk ceiling, which {@code /lp reload} can change. */
    public static int maxChunks() {
        return LPConfig.get().maxChunkRadius();
    }

    /** The configured block ceiling, which {@code /lp reload} can change. */
    public static int maxBlocks() {
        return LPConfig.get().maxBlockRadius();
    }

    /** The widest value the argument itself will parse. */
    public static int hardMaxBlocks() {
        return 64_000;
    }
}

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
package dev.locateplus.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

/**
 * The set of chunks a scan will visit, ordered nearest-first.
 *
 * Nearest-first ordering means {@code /locate} usually finds its answer in the first handful of
 * chunks and can stop early, and partial results stay meaningful if a scan is cut short.
 */
public final class ScanRegion {

    private final ChunkPos origin;
    private final int chunkRadius;
    private final List<ChunkPos> chunks;
    private final boolean forceload;

    /** Lazily built index of {@link #chunks} for {@link #containsPosition}. */
    private volatile java.util.Set<Long> chunkKeys;

    private ScanRegion(ChunkPos origin, int chunkRadius, List<ChunkPos> chunks, boolean forceload) {
        this.origin = origin;
        this.chunkRadius = chunkRadius;
        this.chunks = chunks;
        this.forceload = forceload;
    }

    /** Region reaching {@code chunkRadius} chunks out from the player in every direction. */
    public static ScanRegion ofChunkCount(BlockPos centre, int chunkRadius, boolean forceload) {
        return ofChunkRadius(centre, Math.max(0, chunkRadius), forceload);
    }

    /**
     * Region covering every chunk within {@code chunkRadius} chunks of the origin, as a disc.
     *
     * Used for block-radius scans, where the radius is a real distance rather than a count.
     */
    public static ScanRegion ofChunkRadius(BlockPos centre, int chunkRadius, boolean forceload) {
        ChunkPos origin = new ChunkPos(centre);
        List<ChunkPos> list = new ArrayList<>();

        long limitSq = (long) chunkRadius * chunkRadius;
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                if ((long) dx * dx + (long) dz * dz > limitSq) {
                    continue; // outside the disc, trims the box corners
                }
                list.add(new ChunkPos(origin.x + dx, origin.z + dz));
            }
        }
        list.sort((a, b) -> Long.compare(distanceSq(origin, a), distanceSq(origin, b)));
        return new ScanRegion(origin, chunkRadius, list, forceload);
    }

    /**
     * Build the region a {@link dev.locateplus.util.Radius} describes, honouring the unit the
     * player actually typed.
     */
    public static ScanRegion forRadius(BlockPos centre, dev.locateplus.util.Radius radius,
                                       boolean forceload) {
        return forRadius(centre, radius, forceload, null);
    }

    /**
     * As {@link #forRadius(BlockPos, dev.locateplus.util.Radius, boolean)}, with the world needed
     * to answer {@link dev.locateplus.util.Radius.Unit#LOADED}.
     */
    public static ScanRegion forRadius(BlockPos centre, dev.locateplus.util.Radius radius,
                                       boolean forceload, ServerLevel world) {
        if (radius.isLoadedArea() && world != null) {
            return ofLoadedChunks(centre, world);
        }
        return radius.typedUnit() == dev.locateplus.util.Radius.Unit.CHUNKS
                ? ofChunkCount(centre, radius.chunks(), forceload)
                : ofBlockRadius(centre, radius.blocks(), forceload);
    }

    /** Region covering a radius given in blocks. */
    public static ScanRegion ofBlockRadius(BlockPos centre, int blockRadius, boolean forceload) {
        int chunkRadius = ((blockRadius + 15) >> 4) + 1;
        return ofChunkRadius(centre, chunkRadius, forceload);
    }

    private static long distanceSq(ChunkPos a, ChunkPos b) {
        long dx = (long) a.x - b.x;
        long dz = (long) a.z - b.z;
        return dx * dx + dz * dz;
    }

    public ChunkPos origin() {
        return origin;
    }

    public int chunkRadius() {
        return chunkRadius;
    }

    /** Nearest-first chunk list. */
    public List<ChunkPos> chunks() {
        return chunks;
    }

    public int totalChunks() {
        return chunks.size();
    }

    public boolean forceload() {
        return forceload;
    }

    /**
     * Furthest horizontal distance, in blocks, that any chunk in this region reaches.
     *
     * Lets an entity search bound itself by the chunks actually selected rather than by a block
     * radius, which is meaningless when the player asked for a chunk count.
     */
    public int blockExtent() {
        int maxChunks = 0;
        for (ChunkPos pos : chunks) {
            maxChunks = Math.max(maxChunks, Math.max(
                    Math.abs(pos.x - origin.x), Math.abs(pos.z - origin.z)));
        }
        return (maxChunks + 1) * 16;
    }

    /** Whether a world position falls in a chunk this region covers. */
    public boolean containsPosition(double x, double z) {
        return chunkSet().contains(ChunkPos.asLong(
                net.minecraft.util.Mth.floor(x) >> 4,
                net.minecraft.util.Mth.floor(z) >> 4));
    }

    /**
     * Packed chunk keys, built on first use.
     *
     * A scan can cover tens of thousands of chunks and the range test runs once per entity, so a
     * linear walk of the chunk list would be quadratic.
     */
    private java.util.Set<Long> chunkSet() {
        java.util.Set<Long> set = chunkKeys;
        if (set == null) {
            set = new java.util.HashSet<>(Math.max(16, chunks.size() * 2));
            for (ChunkPos pos : chunks) {
                set.add(pos.toLong());
            }
            chunkKeys = set;
        }
        return set;
    }

    /** Furthest ring searched for resident chunks. Far past any normal view distance. */
    private static final int MAX_LOADED_REACH = 64;

    /** Empty rings tolerated before the search concludes it has left the loaded area. */
    private static final int EMPTY_RING_TOLERANCE = 3;

    /**
     * Every chunk the server currently holds in memory, nearest to {@code centre} first.
     *
     * Force-loading is meaningless here and is not offered: the region is defined as what is
     * already loaded, so there is nothing absent to pull in.
     */
    public static ScanRegion ofLoadedChunks(BlockPos centre, ServerLevel world) {
        ChunkPos origin = new ChunkPos(centre);
        List<ChunkPos> list = new ArrayList<>();

        // Grown ring by ring from the player and stopped once the rings run dry, rather than
        // sweeping a fixed square.
        int emptyRings = 0;
        for (int ring = 0; ring <= MAX_LOADED_REACH && emptyRings < EMPTY_RING_TOLERANCE; ring++) {
            boolean any = false;
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue; // perimeter only; the inside was covered by an earlier ring
                    }
                    int cx = origin.x + dx;
                    int cz = origin.z + dz;
                    if (world.hasChunk(cx, cz)) {
                        list.add(new ChunkPos(cx, cz));
                        any = true;
                    }
                }
            }
            emptyRings = any ? 0 : emptyRings + 1;
        }
        list.sort((a, b) -> Long.compare(distanceSq(origin, a), distanceSq(origin, b)));
        return new ScanRegion(origin, 0, list, false);
    }

    /** How many of these chunks are already in memory. Used for the pre-scan warning. */
    public int countLoaded(ServerLevel world) {
        int loaded = 0;
        for (ChunkPos pos : chunks) {
            if (world.hasChunk(pos.x, pos.z)) {
                loaded++;
            }
        }
        return loaded;
    }
}

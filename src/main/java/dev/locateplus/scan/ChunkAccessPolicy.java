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

import dev.locateplus.core.LPLog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Decides how a scan is allowed to obtain chunks, and cleans up afterwards.
 *
 * Without {@code forceload} a scan only ever touches chunks already in memory, it must never
 * silently generate terrain. With {@code forceload} chunks are pulled in via the vanilla ticket
 * system.
 */
public final class ChunkAccessPolicy implements AutoCloseable {

    private final ServerLevel world;
    private final boolean forceload;
    /**
     * Forced chunks not yet released, in the order they were acquired.
     *
     * Insertion ordered so the oldest, which is the furthest behind the scan and least likely to be
     * wanted again, is always the next to go.
     */
    private final Set<Long> addedTickets = new LinkedHashSet<>();

    /** Forced chunks kept behind the scan position before the oldest is released. */
    private static final int RELEASE_LAG = 64;

    private int skippedUnloaded;
    private int releasedDuringScan;

    public ChunkAccessPolicy(ServerLevel world, boolean forceload) {
        this.world = world;
        this.forceload = forceload;
    }

    /**
     *
     * @return the chunk to scan, or {@code null} if it is unavailable under this policy
     */
    public LevelChunk acquire(ChunkPos pos) {
        if (!forceload) {
            if (!world.hasChunk(pos.x, pos.z)) {
                skippedUnloaded++;
                return null;
            }
            // getWorldChunk(...) returns null rather than loading, which is exactly what we want.
            return world.getChunkSource().getChunkNow(pos.x, pos.z);
        }

        boolean alreadyLoaded = world.hasChunk(pos.x, pos.z);
        if (!alreadyLoaded) {
            // A forced ticket keeps the chunk resident until the scan has moved past it.
            world.setChunkForced(pos.x, pos.z, true);
            addedTickets.add(pos.toLong());
            trimTickets();
        }

        ChunkAccess chunk = world.getChunk(pos.x, pos.z, ChunkStatus.FULL, true);
        if (chunk instanceof LevelChunk worldChunk) {
            return worldChunk;
        }
        skippedUnloaded++;
        return null;
    }

    /**
     * Release the oldest tickets once more than {@link #RELEASE_LAG} are held.
     *
     * This is what keeps a thirty thousand chunk scan inside the same memory as a fifty chunk one.
     * The chunks released are the ones furthest behind the scan, which has already read them and
     * will not return.
     */
    private void trimTickets() {
        while (addedTickets.size() > RELEASE_LAG) {
            java.util.Iterator<Long> oldest = addedTickets.iterator();
            long packed = oldest.next();
            oldest.remove();
            release(packed);
            releasedDuringScan++;
        }
    }

    private void release(long packed) {
        ChunkPos pos = new ChunkPos(packed);
        try {
            world.setChunkForced(pos.x, pos.z, false);
        } catch (Throwable t) {
            LPLog.error("Failed to release forced chunk " + pos, t);
        }
    }

    /** Chunks that could not be scanned, reported in chat and in export headers. */
    public int skippedUnloaded() {
        return skippedUnloaded;
    }

    /** Forced chunks still held. Bounded by {@link #RELEASE_LAG} however large the scan is. */
    public int forcedChunkCount() {
        return addedTickets.size();
    }

    /** Forced chunks already handed back while the scan was still running. */
    public int releasedDuringScan() {
        return releasedDuringScan;
    }

    /** Release whatever is still held. Safe to call twice, and safe after a cancellation. */
    @Override
    public void close() {
        if (addedTickets.isEmpty()) {
            return;
        }
        for (long packed : addedTickets) {
            release(packed);
        }
        addedTickets.clear();
    }
}

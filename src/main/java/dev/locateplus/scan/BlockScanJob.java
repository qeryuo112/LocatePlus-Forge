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
import dev.locateplus.core.ScanJob;
import dev.locateplus.model.ScanResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Walks chunk sections counting blocks, in time slices spread across ticks. */
public final class BlockScanJob implements ScanJob {

    private final ServerLevel world;
    private final ScanRegion region;
    private final ChunkAccessPolicy access;
    private final BlockMatcher matcher;
    private final ScanResult result;
    private final boolean collectPositions;
    private final int blockRadius;
    private final long radiusSq;
    private final Vec3 origin;
    private final Consumer<ScanResult> onComplete;
    private final Consumer<Throwable> onError;
    private final boolean stopWhenFound;

    private final List<ChunkPos> chunks;
    private int chunkIndex;

    /** Resume state: the chunk being scanned and the next section index within it. */
    private LevelChunk currentChunk;
    private ChunkPos currentPos;
    private int sectionCursor;

    /**
     * Cache of block -> id; Registries.BLOCK.getId is a map lookup we would otherwise repeat
     * millions of times.
     */
    private final Map<net.minecraft.world.level.block.Block, ResourceLocation> idCache = new HashMap<>(64);

    /**
     * The tally used for the previous match, kept so a run of identical blocks costs no lookups.
     */
    private net.minecraft.world.level.block.Block lastBlock;
    private ResourceLocation lastId;
    private dev.locateplus.model.BlockTally lastTally;

    private final long startNanos = System.nanoTime();
    private boolean finished;
    private boolean foundAny;

    /**
     * The palette test, built once.
     *
     * A method reference is an object, and creating one per section meant an allocation for every
     * sixteen-block cube in the scan.
     */
    private final java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> paletteTest;

    /** Y band to read, from the config and clamped to what this dimension actually has. */
    private final int minY;
    private final int maxY;

    public BlockScanJob(ServerLevel world, ScanRegion region, BlockMatcher matcher,
                        ScanResult result, boolean collectPositions, int blockRadius,
                        Vec3 origin, boolean stopWhenFound,
                        Consumer<ScanResult> onComplete, Consumer<Throwable> onError) {
        this.world = world;
        this.region = region;
        this.access = new ChunkAccessPolicy(world, region.forceload());
        this.matcher = matcher;
        this.result = result;
        this.collectPositions = collectPositions;
        this.blockRadius = blockRadius;
        this.radiusSq = blockRadius > 0 ? (long) blockRadius * blockRadius : Long.MAX_VALUE;
        this.origin = origin;
        this.stopWhenFound = stopWhenFound;
        this.onComplete = onComplete;
        this.onError = onError;
        this.chunks = region.chunks();
        this.minY = dev.locateplus.core.LPConfig.get().scanMinY(world);
        this.maxY = dev.locateplus.core.LPConfig.get().scanMaxY(world);
        this.paletteTest = matcher::matches;
    }

    @Override
    public String describe() {
        return "block scan (" + matcher.label() + ", " + region.totalChunks() + " chunks)";
    }

    @Override
    public boolean step(long deadlineNanos) {
        if (finished) {
            return true;
        }
        try {
            while (true) {
                // Pick up the next chunk when the previous one is exhausted.
                if (currentChunk == null) {
                    if (chunkIndex >= chunks.size()) {
                        complete();
                        return true;
                    }
                    if (System.nanoTime() >= deadlineNanos) {
                        return false;
                    }
                    currentPos = chunks.get(chunkIndex++);
                    currentChunk = access.acquire(currentPos);
                    sectionCursor = 0;
                    if (currentChunk == null) {
                        continue;
                    }
                    result.addChunkScanned();
                }

                LevelChunkSection[] sections = currentChunk.getSections();
                while (sectionCursor < sections.length) {
                    if (System.nanoTime() >= deadlineNanos) {
                        return false; // resume mid-chunk next tick
                    }
                    scanSection(sections[sectionCursor], sectionCursor);
                    sectionCursor++;
                }

                currentChunk = null;

                // /locate stops once it has a hit: chunks are ordered nearest-first, and any
                // remaining chunk starts farther away than the one that produced the match.
                if (stopWhenFound && foundAny) {
                    complete();
                    return true;
                }
            }
        } catch (Throwable t) {
            fail(t);
            return true;
        }
    }

    private void scanSection(LevelChunkSection section, int sectionIndex) {
        if (section == null || section.hasOnlyAir()) {
            return;
        }
        if (!sectionMayContainMatch(section)) {
            return;
        }

        int sectionBottom = currentChunk.getMinBuildHeight() + (sectionIndex << 4);

        // A section sitting wholly outside the configured band is skipped without being read.
        // Most of a scan's cost is here, so a narrowed band is what makes the setting worth having.
        if (sectionBottom > maxY || sectionBottom + 15 < minY) {
            return;
        }
        int firstRow = Math.max(0, minY - sectionBottom);
        int lastRow = Math.min(15, maxY - sectionBottom);

        int startX = currentPos.getMinBlockX();
        int startZ = currentPos.getMinBlockZ();
        long scannedHere = 0;

        // Pulled out of the loop: a field read per block is a real cost at four million of them.
        final boolean bounded = blockRadius > 0;
        final double originX = origin.x;
        final double originY = origin.y;
        final double originZ = origin.z;

        for (int y = firstRow; y <= lastRow; y++) {
            int worldY = sectionBottom + y;
            double dy = originY - (worldY + 0.5);
            double dySq = dy * dy;

            for (int x = 0; x < 16; x++) {
                int worldX = startX + x;
                double dx = originX - (worldX + 0.5);
                double dxSq = dx * dx;

                // Every column in this row shares an X, so a row wholly outside the radius is
                // skipped without touching its sixteen blocks.
                if (bounded && dxSq > radiusSq) {
                    scannedHere += 16;
                    continue;
                }

                for (int z = 0; z < 16; z++) {
                    scannedHere++;

                    BlockState state = section.getBlockState(x, y, z);
                    if (state.isAir() || !matcher.matches(state)) {
                        continue;
                    }

                    int worldZ = startZ + z;
                    // Horizontal radius: the whole Y column at a given X/Z is in range, so ores
                    // directly below you are found no matter how high you are standing.
                    double dz = originZ - (worldZ + 0.5);
                    double flatSq = dxSq + dz * dz;
                    if (bounded && flatSq > radiusSq) {
                        continue;
                    }
                    // True distance is still what "nearest" means when ranking results. Built from
                    // the parts already computed rather than by asking the vector again.
                    double distSq = flatSq + dySq;

                    net.minecraft.world.level.block.Block block = state.getBlock();
                    if (block != lastBlock) {
                        lastBlock = block;
                        lastId = idCache.computeIfAbsent(block, BuiltInRegistries.BLOCK::getKey);
                        lastTally = result.tally(lastId, collectPositions);
                    }
                    lastTally.record(BlockPos.asLong(worldX, worldY, worldZ), distSq);
                    foundAny = true;
                }
            }
        }
        result.addPositionsScanned(scannedHere);
    }

    /**
     * Ask the section's palette whether any state it contains could match. {@code hasAny} walks the
     * palette rather than the 4096 block positions.
     */
    private boolean sectionMayContainMatch(LevelChunkSection section) {
        try {
            return section.maybeHas(paletteTest);
        } catch (Throwable t) {
            // If a modded palette misbehaves, fall back to scanning the section fully.
            return true;
        }
    }

    private void complete() {
        if (finished) {
            return;
        }
        finished = true;
        result.addChunksSkipped(access.skippedUnloaded());
        result.finish((System.nanoTime() - startNanos) / 1_000_000L);
        access.close();
        try {
            onComplete.accept(result);
        } catch (Throwable t) {
            LPLog.error("Block scan completion handler failed", t);
        }
    }

    private void fail(Throwable t) {
        if (finished) {
            return;
        }
        finished = true;
        access.close();
        LPLog.error("Block scan failed", t);
        try {
            onError.accept(t);
        } catch (Throwable ignored) {
            // reporting must not throw
        }
    }

    @Override
    public void onCancelled(Throwable cause) {
        if (!finished) {
            finished = true;
            access.close();
        }
    }
}

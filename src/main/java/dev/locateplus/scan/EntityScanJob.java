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
import dev.locateplus.entity.EntityQuery;
import dev.locateplus.entity.TargetSpec;
import dev.locateplus.model.EntityRecord;
import dev.locateplus.model.ScanResult;
import dev.locateplus.visual.BlockBeacon;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Collects entities matching a {@link TargetSpec}.
 *
 * Three details decide whether {@code /locate entity} actually finds things: the region test,
 * the player list, and the distance measure.
 */
public final class EntityScanJob implements ScanJob {

    private final ServerLevel world;
    private final CommandSourceStack source;
    private final ScanRegion region;
    private final ChunkAccessPolicy access;
    private final TargetSpec spec;
    private final ScanResult result;
    private final int blockRadius;
    private final Vec3 origin;
    private final Consumer<ScanResult> onComplete;
    private final Consumer<Throwable> onError;

    private final List<ChunkPos> chunks;
    private int chunkIndex;
    private final Set<UUID> seen = new HashSet<>();
    private final long startNanos = System.nanoTime();

    private EntityQuery query;
    private boolean bound;
    private boolean finished;

    public EntityScanJob(ServerLevel world, CommandSourceStack source, ScanRegion region,
                         TargetSpec spec, ScanResult result, int blockRadius, Vec3 origin,
                         Consumer<ScanResult> onComplete, Consumer<Throwable> onError) {
        this.world = world;
        this.source = source;
        this.region = region;
        this.access = new ChunkAccessPolicy(world, region.forceload());
        this.spec = spec;
        this.result = result;
        this.blockRadius = blockRadius;
        this.origin = origin;
        this.onComplete = onComplete;
        this.onError = onError;
        this.chunks = region.chunks();
    }

    @Override
    public String describe() {
        return "entity scan (" + spec.label() + ", " + region.totalChunks() + " chunks)";
    }

    @Override
    public boolean step(long deadlineNanos) {
        if (finished) {
            return true;
        }
        try {
            // Phase 1: make the chunks resident (only does work when forceload was requested).
            while (chunkIndex < chunks.size()) {
                if (System.nanoTime() >= deadlineNanos) {
                    return false;
                }
                ChunkPos pos = chunks.get(chunkIndex++);
                if (access.acquire(pos) != null) {
                    result.addChunkScanned();
                }
            }

            // Phase 2: bind the selector now that the world is in the state the player expects.
            if (!bound) {
                query = spec.bind(source);
                bound = true;
            }

            collect();
            complete();
            return true;
        } catch (Throwable t) {
            fail(t);
            return true;
        }
    }

    private void collect() {
        if (spec.playersOnly()) {
            collectPlayers();
        } else {
            collectFromWorld();
            // Players live outside chunk entity storage; sweep them in too unless the query is
            // structurally incapable of matching one.
            collectPlayers();
        }
    }

    private void collectPlayers() {
        for (ServerPlayer player : world.players()) {
            consider(player);
        }
    }

    private void collectFromWorld() {
        AABB box = searchBox();
        // getOtherEntities(null, box) returns every entity type, including item entities and
        // entities whose class does not extend LivingEntity.
        List<Entity> found = world.getEntities((Entity) null, box, e -> true);
        for (Entity entity : found) {
            consider(entity);
        }
    }

    /** The volume searched for entities. */
    private AABB searchBox() {
        double r = blockRadius > 0 ? blockRadius : region.blockExtent() + 16;
        return new AABB(
                origin.x - r, dev.locateplus.core.LPConfig.get().scanMinY(world), origin.z - r,
                origin.x + r, dev.locateplus.core.LPConfig.get().scanMaxY(world) + 1.0,
                origin.z + r);
    }

    /**
     * Whether a position lies outside what this scan covers.
     *
     * This matters most for players, who are gathered from {@code world.players()} rather than
     * from the search box and so have nothing else constraining them.
     */
    private boolean outOfRange(double x, double z) {
        if (blockRadius > 0) {
            double dx = origin.x - x;
            double dz = origin.z - z;
            return dx * dx + dz * dz > (double) blockRadius * blockRadius;
        }
        return !region.containsPosition(x, z);
    }

    private void consider(Entity entity) {
        if (entity == null || entity.isRemoved()) {
            return;
        }
        if (BlockBeacon.isMarker(entity)) {
            return; // this mod's own block marker, not something the world contains
        }
        if (!seen.add(entity.getUUID())) {
            return; // already counted (players are swept twice by design)
        }
        if (query != null && !query.matches(entity)) {
            return;
        }
        if (outOfRange(entity.getX(), entity.getZ())) {
            return;
        }
        result.addEntity(EntityRecord.of(entity, origin));
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
            LPLog.error("Entity scan completion handler failed", t);
        }
    }

    private void fail(Throwable t) {
        if (finished) {
            return;
        }
        finished = true;
        access.close();
        LPLog.error("Entity scan failed", t);
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

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
package dev.locateplus.visual;

import dev.locateplus.core.LPLog;
import dev.locateplus.core.TickTasks;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Draws the boundary of a scan region as a standing line of particles. */
public final class RegionOutline {

    /** Furthest a post is drawn. Past this the client discards the packet anyway. */
    private static final int DRAW_RANGE = 480;

    /** Redraw interval. Dust lives about a second, so this keeps the line looking continuous. */
    private static final int REFRESH_TICKS = 15;

    /** Spacing between posts along the boundary, in blocks. */
    private static final int POST_SPACING = 8;

    /** Dust scale, for the red warning posts and the anchor. Larger than vanilla so it reads. */
    private static final float POST_SCALE = 2.0f;

    /** Particles per post. Kept low so a wide boundary stays a line rather than a wall. */
    private static final int POST_DENSITY = 3;

    /** Height of one post, in blocks. Tall enough to clear grass and low walls. */
    private static final double POST_HEIGHT = 2.5;

    /** Ceiling on posts drawn in one refresh. */
    private static final int MAX_POSTS = 1_500;

    /** Red, for a chunk that is not loaded. */
    private static final Vector3f UNLOADED = new Vector3f(1.00f, 0.15f, 0.15f);

    /** Blue, for the spot the command was run on. */
    private static final Vector3f ANCHOR = new Vector3f(0.25f, 0.70f, 1.00f);

    /** One live outline per player. Starting another replaces it. */
    private static final Map<UUID, RegionOutline> ACTIVE = new ConcurrentHashMap<>();

    private final ServerPlayer player;
    private final ServerLevel world;
    private final Vec3 anchor;

    /** Height the command was run at. The outline is drawn on this plane and stays there. */
    private final double anchorY;

    /** Boundary posts, as parallel arrays to keep the redraw loop allocation free. */
    private final int[] postX;
    private final int[] postZ;
    private final long[] postChunk;

    private volatile boolean cancelled;

    /**
     * Scratch list of in-range posts, distance packed above the index so a plain sort orders by
     * proximity. Reused between refreshes to keep a task that runs four times a second allocation
     * free.
     */
    private final java.util.List<Long> visible = new java.util.ArrayList<>();

    /**
     * The two dust effects, built once.
     *
     * A redraw happens four times a second for as long as an outline is up, and these are
     * immutable, so rebuilding them each time was pure allocation.
     */
    private static final DustParticleOptions UNLOADED_DUST =
            new DustParticleOptions(UNLOADED, POST_SCALE);
    private static final DustParticleOptions ANCHOR_DUST =
            new DustParticleOptions(ANCHOR, POST_SCALE + 1.0f);

    private RegionOutline(ServerPlayer player, ServerLevel world, Vec3 anchor,
                          Map<Long, Long> posts) {
        this.player = player;
        this.world = world;
        this.anchor = anchor;
        this.anchorY = anchor.y;

        int size = posts.size();
        this.postX = new int[size];
        this.postZ = new int[size];
        this.postChunk = new long[size];

        int i = 0;
        for (Map.Entry<Long, Long> entry : posts.entrySet()) {
            long packed = entry.getKey();
            postX[i] = (int) (packed >> 32);
            postZ[i] = (int) packed;
            postChunk[i] = entry.getValue();
            i++;
        }
    }

    // ---- lifecycle ----------------------------------------------------------------------------

    /**
     * Outline the chunks a chunk radius covers.
     *
     * The boundary is the ragged edge of the chunk disc rather than any circle. Only the edge is
     * worked out: the chunks inside it are never visited, so the cost follows the radius rather
     * than the area and a huge request is as cheap as a small one.
     */
    public static RegionOutline showChunkRadius(ServerPlayer player, ServerLevel world,
                                                Vec3 anchor, ChunkPos origin, int chunkRadius,
                                                int seconds) {
        return start(player, world, anchor, discBoundary(origin, chunkRadius), seconds);
    }

    /**
     * Outline the circle a block radius describes.
     *
     * A block radius is filtered as a true distance, so the circle is the honest boundary. The
     * chunks touched reach further, and drawing those instead would overstate the area by roughly a
     * chunk in every direction.
     */
    public static RegionOutline showCircle(ServerPlayer player, ServerLevel world,
                                           Vec3 anchor, int blockRadius, int seconds) {
        return start(player, world, anchor, circleBoundary(anchor, blockRadius), seconds);
    }

    private static RegionOutline start(ServerPlayer player, ServerLevel world, Vec3 anchor,
                                       Map<Long, Long> posts, int seconds) {
        hide(player);

        RegionOutline outline = new RegionOutline(player, world, anchor, posts);
        ACTIVE.put(player.getUUID(), outline);

        int repeats = Math.max(1, (seconds * 20) / REFRESH_TICKS);
        TickTasks.scheduleRepeating(REFRESH_TICKS, repeats, outline::draw);
        return outline;
    }

    /** Stop whatever outline this player has running. */
    public static boolean hide(ServerPlayer player) {
        RegionOutline previous = ACTIVE.remove(player.getUUID());
        if (previous == null) {
            return false;
        }
        previous.cancelled = true;
        return true;
    }

    /** Drop every outline. Called on server stop so nothing survives a restart. */
    public static void clear() {
        for (RegionOutline outline : ACTIVE.values()) {
            outline.cancelled = true;
        }
        ACTIVE.clear();
    }

    // ---- boundary construction ----------------------------------------------------------------

    /**
     * Widest {@code dx} that stays inside a disc of {@code limitSq}, for one row.
     *
     * Derived from the radius instead of tested per chunk, which is what keeps the boundary
     * linear. The square root is nudged both ways afterwards so the result is exact rather than
     * trusting floating point at the edge.
     *
     * @return the largest offset in the row, or -1 when the row misses the disc entirely
     */
    private static int rowHalfWidth(long limitSq, int dz) {
        long dzSq = (long) dz * dz;
        if (dzSq > limitSq) {
            return -1;
        }
        int half = (int) Math.sqrt((double) (limitSq - dzSq));
        while ((long) (half + 1) * (half + 1) + dzSq <= limitSq) {
            half++;
        }
        while (half >= 0 && (long) half * half + dzSq > limitSq) {
            half--;
        }
        return half;
    }

    /**
     * Posts along every chunk edge of a disc that faces outwards.
     *
     * A row is compared with its neighbours to find which of its chunks are actually exposed. Only
     * those get posts, so the work is proportional to the edge and not to the chunks enclosed.
     *
     * @return post position packed as x,z to the chunk it belongs to
     */
    private static Map<Long, Long> discBoundary(ChunkPos origin, int chunkRadius) {
        long limitSq = (long) chunkRadius * chunkRadius;
        Map<Long, Long> posts = new LinkedHashMap<>();

        for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
            int half = rowHalfWidth(limitSq, dz);
            if (half < 0) {
                continue;
            }
            int above = rowHalfWidth(limitSq, dz - 1);
            int below = rowHalfWidth(limitSq, dz + 1);

            for (int dx = -half; dx <= half; dx++) {
                boolean westOpen = dx == -half;
                boolean eastOpen = dx == half;
                // A neighbouring row that does not reach this far leaves the chunk exposed.
                boolean northOpen = above < 0 || Math.abs(dx) > above;
                boolean southOpen = below < 0 || Math.abs(dx) > below;
                if (!westOpen && !eastOpen && !northOpen && !southOpen) {
                    continue; // fully enclosed, nothing to draw
                }

                ChunkPos pos = new ChunkPos(origin.x + dx, origin.z + dz);
                long owner = pos.toLong();
                int x0 = pos.getMinBlockX();
                int z0 = pos.getMinBlockZ();

                if (northOpen) {
                    addRun(posts, owner, x0, z0, POST_SPACING, true);
                }
                if (southOpen) {
                    addRun(posts, owner, x0, z0 + 16, POST_SPACING, true);
                }
                if (westOpen) {
                    addRun(posts, owner, x0, z0, POST_SPACING, false);
                }
                if (eastOpen) {
                    addRun(posts, owner, x0 + 16, z0, POST_SPACING, false);
                }
            }
        }
        return posts;
    }

    /**
     * Posts along one 16-block chunk edge. Shared corners collapse, since the key is the position.
     */
    private static void addRun(Map<Long, Long> posts, long owner, int x, int z,
                               int spacing, boolean alongX) {
        for (int step = 0; step <= 16; step += spacing) {
            long key = alongX ? pack(x + step, z) : pack(x, z + step);
            posts.putIfAbsent(key, owner);
        }
    }

    /**
     * Posts around a circle of {@code radius} blocks.
     *
     * The step count is derived from the circumference so spacing stays even at any size, and
     * capped so a huge radius produces a dashed circle rather than an unbounded packet flood.
     */
    private static Map<Long, Long> circleBoundary(Vec3 centre, int radius) {
        double circumference = 2.0 * Math.PI * radius;
        int steps = (int) Math.max(24, circumference / POST_SPACING);

        Map<Long, Long> posts = new LinkedHashMap<>(steps * 2);
        for (int i = 0; i < steps; i++) {
            double angle = (2.0 * Math.PI * i) / steps;
            int x = (int) Math.round(centre.x + Math.cos(angle) * radius);
            int z = (int) Math.round(centre.z + Math.sin(angle) * radius);
            posts.putIfAbsent(pack(x, z), ChunkPos.asLong(x >> 4, z >> 4));
        }
        return posts;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    // ---- drawing ------------------------------------------------------------------------------

    private void draw() {
        if (cancelled || player.isRemoved() || player.serverLevel() != world) {
            return;
        }
        try {
            // Fixed at the height the command was run.
            double y = anchorY;
            double px = player.getX();
            double pz = player.getZ();
            long rangeSq = (long) DRAW_RANGE * DRAW_RANGE;


            // Gather what is in range, nearest first, so the budget trims the far edge rather than
            // whichever posts the array happened to reach.
            visible.clear();
            for (int i = 0; i < postX.length; i++) {
                double dx = px - postX[i];
                double dz = pz - postZ[i];
                double distSq = dx * dx + dz * dz;
                if (distSq > rangeSq) {
                    continue;
                }
                visible.add(((long) (distSq * 4.0) << 20) | i);
            }
            if (visible.size() > MAX_POSTS) {
                visible.sort(null);
                visible.subList(MAX_POSTS, visible.size()).clear();
            }

            for (long packed : visible) {
                int i = (int) (packed & 0xFFFFF);
                // Unpacked directly rather than through a ChunkPos, which would be an object per
                // post per redraw.
                long owner = postChunk[i];
                boolean chunkLoaded = world.hasChunk(
                        ChunkPos.getX(owner), ChunkPos.getZ(owner));

                // Each particle is asked for on its own at a set height. Asking for several in
                // one call spreads them at random, which leaves every post a different height
                // and a fresh one on each refresh.
                for (int step = 0; step < POST_DENSITY; step++) {
                    double at = y + POST_HEIGHT * (step + 1) / (POST_DENSITY + 1.0);

                    // Red marks a chunk that is not loaded, so it has to stay separable from the
                    // rest of the line at a glance.
                    ParticleOptions effect = chunkLoaded
                            ? ParticleTypes.END_ROD
                            : UNLOADED_DUST;
                    world.sendParticles(player, effect, true,
                            postX[i], at, postZ[i], 1, 0.0, 0.0, 0.0, 0.0);
                }
            }

            // A taller blue column on the spot the command was run, so the way back and the
            // centre of the area are always obvious. Blue keeps it apart from the boundary.
            world.sendParticles(player, ANCHOR_DUST, true,
                    anchor.x, y + 2.0, anchor.z, 12, 0.05, 2.0, 0.05, 0.0);
        } catch (Throwable t) {
            cancelled = true;
            ACTIVE.remove(player.getUUID(), this);
            LPLog.error("Region outline failed, stopping it", t);
        }
    }

    // ---- reporting ----------------------------------------------------------------------------

    /** How many posts make up this outline. */
    public int postCount() {
        return postX.length;
    }

    /** Posts currently too far from the anchor for a client to render. */
    public int outOfRangeCount() {
        long rangeSq = (long) DRAW_RANGE * DRAW_RANGE;
        int count = 0;
        for (int i = 0; i < postX.length; i++) {
            double dx = anchor.x - postX[i];
            double dz = anchor.z - postZ[i];
            if (dx * dx + dz * dz > rangeSq) {
                count++;
            }
        }
        return count;
    }

    /** The distance past which nothing is drawn, so callers can explain the gap. */
    public static int drawRange() {
        return DRAW_RANGE;
    }

    /**
     * How many chunks a chunk radius covers, counted a row at a time.
     *
     * The same figure a scan would report, without building the list to get it.
     */
    public static long discChunkCount(int chunkRadius) {
        long limitSq = (long) chunkRadius * chunkRadius;
        long total = 0;
        for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
            int half = rowHalfWidth(limitSq, dz);
            if (half >= 0) {
                total += 2L * half + 1L;
            }
        }
        return total;
    }

    /**
     * Extra ring searched around each player, past the view distance.
     *
     * A client is sent chunks slightly beyond what it renders, and a chunk stays resident for a
     * moment after nobody is watching it, so the search is widened rather than trusting the
     * configured number exactly.
     */
    private static final int WATCH_MARGIN = 2;

    /** Half-width of the square checked around world spawn, which stays loaded on its own. */
    private static final int SPAWN_REACH = 12;

    /**
     * How many chunks of a disc are resident.
     *
     * Asked of the places chunks are kept loaded rather than of the disc. A server holds a few
     * hundred chunks whatever radius was typed, and they sit around players, around world spawn
     * and in the force-loaded set, so those are walked and each candidate tested against the
     * disc. Sweeping the disc instead would cost its area, which is the thing this command is
     * built to avoid.
     */
    public static long countLoadedInDisc(ServerLevel world, ChunkPos origin, int chunkRadius) {
        long limitSq = (long) chunkRadius * chunkRadius;
        Set<Long> seen = new HashSet<>();
        long loaded = 0;

        for (long packed : world.getForcedChunks()) {
            if (seen.add(packed)
                    && inDisc(origin, limitSq, ChunkPos.getX(packed),
                            ChunkPos.getZ(packed))
                    && world.hasChunk(ChunkPos.getX(packed),
                            ChunkPos.getZ(packed))) {
                loaded++;
            }
        }

        int reach = world.getServer().getPlayerList().getViewDistance() + WATCH_MARGIN;
        for (ServerPlayer watcher : world.players()) {
            loaded += countAround(world, origin, limitSq, seen,
                    watcher.chunkPosition(), reach);
        }

        loaded += countAround(world, origin, limitSq, seen,
                new ChunkPos(world.getSharedSpawnPos()), SPAWN_REACH);

        return loaded;
    }

    /** Resident chunks in a square around {@code centre} that also fall inside the disc. */
    private static long countAround(ServerLevel world, ChunkPos origin, long limitSq,
                                    Set<Long> seen, ChunkPos centre, int reach) {
        long loaded = 0;
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                int cx = centre.x + dx;
                int cz = centre.z + dz;
                if (!seen.add(ChunkPos.asLong(cx, cz))) {
                    continue;
                }
                if (inDisc(origin, limitSq, cx, cz) && world.hasChunk(cx, cz)) {
                    loaded++;
                }
            }
        }
        return loaded;
    }

    private static boolean inDisc(ChunkPos origin, long limitSq, int cx, int cz) {
        long dx = (long) cx - origin.x;
        long dz = (long) cz - origin.z;
        return dx * dx + dz * dz <= limitSq;
    }
}

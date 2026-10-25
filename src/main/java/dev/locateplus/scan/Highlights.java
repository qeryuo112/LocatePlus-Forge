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

import dev.locateplus.core.LPConfig;
import dev.locateplus.core.TickTasks;
import dev.locateplus.visual.BlockBeacon;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Visual markers: a glowing cage on a located block, Glowing on the nearest entity. */
public final class Highlights {

    /** Entities this mod has made glow, so they can be cleared on request. */
    private static final Set<UUID> GLOWING = ConcurrentHashMap.newKeySet();

    private Highlights() {
    }

    /**
     * Stop the glow on every entity this mod lit.
     *
     * Only entities recorded here are touched, so a mob glowing for some other reason keeps its
     * effect. The pending expiry timers are dropped as well, since the flag they were going to
     * clear is already off.
     *
     * @return how many entities stopped glowing
     */
    public static int clearGlow(ServerLevel world) {
        return clearGlow(world.getServer());
    }

    /**
     * Stop the glow on every entity this mod lit, in every dimension.
     *
     * Every dimension, not just the one the command was typed in. An entity is looked up by id in
     * one world at a time, so searching only the caller's world would leave anything lit elsewhere
     * glowing while still emptying the record of it, and nothing could ever switch it off again.
     *
     * @return how many entities stopped glowing
     */
    public static int clearGlow(MinecraftServer server) {
        int cleared = 0;
        for (UUID id : GLOWING) {
            Entity entity = find(server, id);
            if (entity == null) {
                continue;
            }
            try {
                if (entity instanceof LivingEntity living) {
                    living.removeEffect(MobEffects.GLOWING);
                } else {
                    entity.setGlowingTag(false);
                }
                cleared++;
            } catch (Throwable ignored) {
                // an entity that will not cooperate must not stop the rest being cleared
            }
        }
        GLOWING.clear();
        TickTasks.clear(TickTasks.Kind.GLOW);
        return cleared;
    }

    /** The entity with this id in whichever dimension currently holds it, or null. */
    private static Entity find(MinecraftServer server, UUID id) {
        for (ServerLevel world : server.getAllLevels()) {
            Entity entity = world.getEntity(id);
            if (entity != null && !entity.isRemoved()) {
                return entity;
            }
        }
        return null;
    }

    /**
     * Switch off a glow flag that would otherwise be written to disk.
     *
     * A living entity holds its glow as a status effect, which counts down on its own and is the
     * game's to manage. Everything else is lit by the entity's own glow flag, and that flag is
     * saved with the entity, so one left set outlives the timer that was going to clear it and
     * comes back lit with nothing tracking it. Called when such an entity leaves, and when the
     * server stops, which are the two moments the flag can reach the save file.
     */
    public static void releaseGlow(Entity entity) {
        if (entity instanceof LivingEntity) {
            return;
        }
        if (GLOWING.contains(entity.getUUID())) {
            entity.setGlowingTag(false);
        }
    }

    /**
     * Turn off every glow this mod applied and forget them, without touching the world afterwards.
     *
     * Called when the server stops, before the worlds are written out.
     */
    public static void releaseAllGlow(MinecraftServer server) {
        if (GLOWING.isEmpty()) {
            return;
        }
        for (UUID id : GLOWING) {
            Entity entity = find(server, id);
            if (entity != null && !(entity instanceof LivingEntity)) {
                try {
                    entity.setGlowingTag(false);
                } catch (Throwable ignored) {
                    // one uncooperative entity must not stop the rest being cleared
                }
            }
        }
        GLOWING.clear();
    }

    /** Mark a located block. */
    public static void markBlock(ServerLevel world, BlockPos pos) {
        markTarget(world, pos);
    }

    /**
     * Put a glowing outline on a block for the configured marker duration.
     *
     * @return whether the block is showing a marker, either the one this call made or one that was
     *         already there. False when the block cannot carry one, so a caller can avoid telling
     *         the player to look for something that is not drawn.
     */
    public static boolean markTarget(ServerLevel world, BlockPos pos) {
        // One outline per block. A key already present means the marker is still live, so there
        // is nothing to add; anything stale was dropped when the last server shut down.
        Marked key = new Marked(world.dimension().location().toString(), pos.asLong());
        if (!MARKED.add(key)) {
            return true;
        }

        BlockState marked = world.getBlockState(pos);
        UUID id = BlockBeacon.show(world, pos);
        if (id == null) {
            MARKED.remove(key);
            return false;
        }

        int lifetime = LPConfig.get().blockMarkerNeverExpires()
                ? NO_EXPIRY
                : LPConfig.get().blockMarkerTicks();
        LIVE.put(key, new Live(id, marked.getBlock(), lifetime));
        return true;
    }

    /** Advance every marker by a tick, dropping the ones that are finished. */
    public static void tick(MinecraftServer server) {
        if (LIVE.isEmpty()) {
            return;
        }
        // Iterated directly. LIVE is a concurrent map whose iterator tolerates the removals this
        // loop makes, so copying it every tick allocated a map for nothing.
        for (Map.Entry<Marked, Live> entry : LIVE.entrySet()) {
            Marked key = entry.getKey();
            Live live = entry.getValue();

            ServerLevel world = server.getLevel(ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.tryParse(key.world())));
            if (world == null) {
                drop(key);
                continue;
            }

            BlockPos pos = BlockPos.of(key.pos());
            // An unloaded chunk reads as air and holds no entity, so neither question can be
            // answered there. The countdown is paused rather than run down: expiring now would
            // leave an entity in the world that nothing is tracking any more, and it would take
            // a restart to notice. The clock resumes when somebody is near enough to see it.
            if (!world.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) {
                continue;
            }

            if (!world.getBlockState(pos).is(live.block())) {
                finish(world, key, live);
                continue;
            }

            if (live.expire() == NO_EXPIRY) {
                continue;
            }
            if (live.expire() <= 0) {
                finish(world, key, live);
                continue;
            }
            LIVE.put(key, live.aged());
        }
    }

    /**
     * Retire a marker whose time is up or whose block has gone.
     *
     * The entry is dropped whether or not the entity could be discarded. Keeping it on the books
     * after a failure sounds safer but is worse: the entity is already gone in every case that
     * matters, and a key that is never released blocks the same block from ever being marked
     * again. Anything genuinely left in the world is caught by {@link #sweepOrphans}.
     */
    private static void finish(ServerLevel world, Marked key, Live live) {
        BlockBeacon.hide(world, live.id());
        drop(key);
    }

    private static void drop(Marked key) {
        LIVE.remove(key);
        MARKED.remove(key);
    }

    /**
     * Discard any marker in a loaded chunk that nothing is tracking.
     *
     * A marker is only ever meant to exist while it has an entry here. One that outlives its
     * entry cannot expire, cannot be found by the block check, and would sit in the world until
     * somebody noticed it, so the tracking map is treated as the authority and anything not in it
     * is removed on sight. This covers every way an entity and its record can come apart,
     * including ones not foreseen here.
     *
     * @return how many were removed
     */
    public static int sweepOrphans(MinecraftServer server) {
        Set<UUID> tracked = new HashSet<>();
        for (Live live : LIVE.values()) {
            tracked.add(live.id());
        }
        int removed = 0;
        for (ServerLevel world : server.getAllLevels()) {
            removed += BlockBeacon.discardUntracked(world, tracked);
        }
        return removed;
    }

    /**
     * Drop all marker bookkeeping.
     *
     * Called when a server stops. The maps are static, and in singleplayer the game keeps running
     * between worlds, so entries left behind would outlive the world that made them: the position
     * would look as though it were already marked, and the next marker put there would never be
     * tracked and so would never expire.
     */
    public static void forgetBeacons() {
        MARKED.clear();
        LIVE.clear();
    }

    /**
     * Remove every glowing block marker.
     *
     * @return how many were removed
     */
    public static int clearBeacons(ServerLevel world) {
        // Every dimension, not just the one the command was typed in: a marker left in the Nether
        // is exactly the sort of thing somebody runs this to be rid of, and it would otherwise
        // need the command running again in each world.
        int cleared = 0;
        for (ServerLevel each : world.getServer().getAllLevels()) {
            cleared += BlockBeacon.hideAll(each);
        }
        MARKED.clear();
        LIVE.clear();
        TickTasks.clear(TickTasks.Kind.BEACON);
        return cleared;
    }

    /** A marked position, told apart from the same coordinates in another dimension. */
    private record Marked(String world, long pos) {
    }

    /** A marker that is out: what to remove, what it is watching, and how long it has left. */
    private record Live(UUID id, Block block, int expire) {
        Live aged() {
            return new Live(id, block, expire - 1);
        }
    }

    /** Lifetime standing for "no time limit", distinct from a countdown that has run out. */
    private static final int NO_EXPIRY = -1;

    /** Markers currently out, by position. */
    private static final Map<Marked, Live> LIVE = new ConcurrentHashMap<>();

    /** Blocks currently outlined, so the same one is never marked twice. */
    private static final Set<Marked> MARKED = ConcurrentHashMap.newKeySet();

    /**
     * Apply the vanilla Glowing effect to an entity.
     *
     * Living entities get a status effect. Everything else, item entities, boats and armour
     * stands, cannot hold effects, so the entity's glow flag is set directly and cleared later by
     * a scheduled task.
     */
    public static void glow(ServerLevel world, Entity entity) {
        GLOWING.add(entity.getUUID());
        if (entity instanceof LivingEntity living) {
            living.addEffect(new MobEffectInstance(
                    MobEffects.GLOWING, LPConfig.get().glowDurationTicks(), 0, false, false, true));
            return;
        }

        entity.setGlowingTag(true);
        UUID id = entity.getUUID();
        TickTasks.schedule(TickTasks.Kind.GLOW, LPConfig.get().glowDurationTicks(), () -> {
            Entity live = world.getEntity(id);
            if (live != null && !live.isRemoved()) {
                live.setGlowingTag(false);
            }
        });
    }
}

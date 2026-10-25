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

import dev.locateplus.core.LPConfig;
import dev.locateplus.core.LPLog;
import dev.locateplus.report.Chat;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;

/**
 * Performs safe teleports and reports the outcome, for both {@code /safetp} and the chat buttons.
 */
public final class TeleportService {

    private TeleportService() {
    }

    /**
     * Move {@code entity} to the nearest safe spot to {@code destination}.
     *
     * Loads the destination chunk first: teleporting into an unloaded chunk is exactly the
     * situation where a naive safety check reads air everywhere and drops the player into the void.
     *
     * When nothing standable is found anywhere near, {@code fallback_to_vanilla_tp} decides what
     * happens. On, the default, the entity goes to the exact position asked for the same way
     * vanilla {@code /tp} would, and the returned spot reports itself as unchecked so the caller
     * can say so. Off, nothing moves and this returns null.
     *
     * @return the spot used, or {@code null} if nowhere safe was found and the fallback is off
     */
    public static SafeSpot teleportSafely(Entity entity, ServerLevel world, Vec3 destination) {
        BlockPos requested = BlockPos.containing(destination);
        int centreX = requested.getX() >> 4;
        int centreZ = requested.getZ() >> 4;

        // The destination chunk, so the block asked for is real rather than empty air.
        loadChunk(world, centreX, centreZ);

        SafeSpot spot = SafeLocator.find(world, requested);

        // A block in a chunk that is not resident reads as nothing, so a landing spot just over a
        // chunk boundary is invisible and the search reports the drop below instead. Widening
        // catches those, but it means generating terrain and searching a second time, so it is
        // only worth doing when a neighbouring chunk is actually missing. With all of them present
        // the first search already saw everything a second one would.
        if (needsWiderLook(spot, requested) && loadNeighbours(world, centreX, centreZ)) {
            SafeSpot wider = SafeLocator.find(world, requested);
            if (wider != null) {
                spot = wider;
            }
        }

        if (spot == null) {
            // Nothing standable within reach. Refusing to move is the cautious answer but it is
            // also a dead end: the player is left where they were with no way to reach what they
            // were looking at. Going anyway is what vanilla /tp does, and the message says the
            // landing was not checked so it is clear which of the two happened.
            if (!LPConfig.get().safeTpGoAnyway()) {
                return null;
            }
            spot = SafeSpot.unchecked(destination, requested);
        }

        Vec3 pos = spot.position();
        if (entity instanceof ServerPlayer player) {
            // Turn to face the target, so the glowing outline is on screen the instant the player
            // arrives rather than somewhere behind them.
            float yaw = spot.isExact() ? player.getYRot()
                    : yawTowards(pos, spot.requested(), player.getYRot());
            float pitch = spot.isExact() ? player.getXRot() : pitchTowards(pos, spot.requested());
            player.teleportTo(world, pos.x, pos.y, pos.z, Collections.emptySet(), yaw, pitch);
            player.fallDistance = 0.0f;
        } else {
            // The world-aware form handles a cross-dimension move itself, and is the one call
            // that has kept the same shape across game versions.
            entity.teleportTo(world, pos.x, pos.y, pos.z, Collections.emptySet(),
                    entity.getYRot(), entity.getXRot());
            entity.fallDistance = 0.0f;
        }

        // Nothing is marked. A teleport is an action, not a search, and leaving an outline behind
        // means the player has to clear something they never asked to see. The commands that find
        // things do their own marking, which is where it belongs.
        return spot;
    }

    /**
     * Whether the neighbouring chunks are worth generating.
     *
     * A landing right by the destination is already the answer, and no amount of extra terrain
     * will improve on it. Nothing found at all, or a spot far enough away to suggest the search
     * ran out of visible blocks, is what makes the wider look worth its cost.
     */
    private static boolean needsWiderLook(SafeSpot spot, BlockPos requested) {
        if (spot == null) {
            return true;
        }
        BlockPos landed = spot.blockPos();
        int dx = Math.abs(landed.getX() - requested.getX());
        int dy = Math.abs(landed.getY() - requested.getY());
        int dz = Math.abs(landed.getZ() - requested.getZ());
        return Math.max(Math.max(dx, dy), dz) > GOOD_ENOUGH;
    }

    /**
     * Make the ring of chunks around the destination resident.
     *
     * @return whether any of them had to be brought in, which is the only case where searching
     *         again can find something the first pass missed
     */
    private static boolean loadNeighbours(ServerLevel world, int centreX, int centreZ) {
        boolean loadedAny = false;
        for (int dx = -SEARCH_CHUNK_REACH; dx <= SEARCH_CHUNK_REACH; dx++) {
            for (int dz = -SEARCH_CHUNK_REACH; dz <= SEARCH_CHUNK_REACH; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (world.hasChunk(centreX + dx, centreZ + dz)) {
                    continue;
                }
                loadChunk(world, centreX + dx, centreZ + dz);
                loadedAny = true;
            }
        }
        return loadedAny;
    }

    /** Bring one chunk up to full detail, logging rather than failing when it will not generate. */
    private static void loadChunk(ServerLevel world, int chunkX, int chunkZ) {
        try {
            world.getChunk(chunkX, chunkZ, ChunkStatus.FULL, true);
        } catch (Throwable t) {
            // A chunk that will not generate is simply one the search cannot use.
            LPLog.error("Could not load chunk " + chunkX + ", " + chunkZ + " for a teleport", t);
        }
    }

    /** Yaw that looks from {@code from} towards the centre of {@code target}. */
    private static float yawTowards(Vec3 from, BlockPos target, float fallback) {
        double dx = (target.getX() + 0.5) - from.x;
        double dz = (target.getZ() + 0.5) - from.z;
        if (Math.abs(dx) < 1.0e-6 && Math.abs(dz) < 1.0e-6) {
            return fallback;
        }
        return (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
    }

    /** Pitch that looks from eye level at {@code from} towards the centre of {@code target}. */
    private static float pitchTowards(Vec3 from, BlockPos target) {
        double dx = (target.getX() + 0.5) - from.x;
        double dy = (target.getY() + 0.5) - (from.y + EYE_HEIGHT);
        double dz = (target.getZ() + 0.5) - from.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        return (float) -Math.toDegrees(Math.atan2(dy, flat));
    }

    /** Standing eye height of a player, used to aim the landing pitch. */
    private static final double EYE_HEIGHT = 1.62;

    /**
     * Chunks loaded either side of the destination when the first look was unconvincing.
     *
     * One ring, nine chunks, reaching at least sixteen blocks past the destination in every
     * direction. That covers a spot beside a pillar or over a chunk line, which is what the wider
     * look exists for.
     */
    private static final int SEARCH_CHUNK_REACH = 1;

    /**
     * How far a landing may be from the destination before the neighbouring chunks are worth
     * generating to look for a better one.
     */
    private static final int GOOD_ENOUGH = 8;

    /**
     * A clickable {@code [Teleport]} button on a result from this mod's own searches.
     *
     * Clicking it fills the chat box with the command rather than running it, unless
     * {@code teleport_button_mode} says otherwise. A results list puts many of these a line apart,
     * and a mistimed click that moves you across the world is worse than one that types a command
     * you can read before sending.
     */
    public static Component teleportButton(BlockPos pos) {
        return teleportButton(pos, LPConfig.get().resultButtonSafeTeleport());
    }

    /**
     * A button on a result from {@code /locate biome}, {@code structure} or {@code poi}.
     *
     * Those name a place rather than a block, so the coordinate can land in the air, in a wall or
     * inside a structure, and they have their own switch for whether the button teleports safely.
     */
    public static Component vanillaTeleportButton(BlockPos pos) {
        return teleportButton(pos, LPConfig.get().biomeButtonSafeTeleport());
    }

    private static Component teleportButton(BlockPos pos, boolean safe) {
        String command = String.format(safe ? "/safetp %d %d %d" : "/tp %d %d %d",
                pos.getX(), pos.getY(), pos.getZ());
        boolean runs = LPConfig.get().teleportButtonRuns();
        net.minecraft.network.chat.ClickEvent.Action action = runs
                ? net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND
                : net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND;
        String hint = runs
                ? (safe ? "Safely teleport to " : "Teleport straight to ") + Chat.coords(pos)
                : "Put this in your chat box, ready to send: " + command;
        return Component.literal("[Teleport]")
                .withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withBold(true)
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(action, command))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                Component.literal(safe
                                        ? hint + "\nIf you cannot stand there you land nearby and "
                                                + "are told which way it is."
                                        : hint + "\nNo safety check, so this can put you inside "
                                                + "a block."))));
    }
}

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

import dev.locateplus.core.LPConfig;
import dev.locateplus.core.LPConstants;
import dev.locateplus.core.LPLog;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Marks a block with a glowing outline that can be seen through terrain. */
public final class BlockBeacon {

    /** Command tag on every marker, so leftovers can be recognised after a restart. */
    public static final String TAG = "locateplus_marker";

    /** How much larger than the block itself to draw the outline. */
    private static final float SCALE = 1.02f;

    /** Shift that keeps the enlarged block centred on the real one. */
    private static final float OFFSET = -(SCALE - 1.0f) / 2.0f;

    /** Fallback outline colour when the configured one cannot be read. */
    private static final int DEFAULT_GLOW_COLOUR = 0x00E5FF;

    /** The sixteen dye names, so a colour can be given as a word instead of a hex code. */
    private static final Map<String, Integer> NAMED_COLOURS = Map.ofEntries(
            Map.entry("white", 0xFFFFFF),
            Map.entry("orange", 0xFF8000),
            Map.entry("magenta", 0xFF00FF),
            Map.entry("light_blue", 0x40C0FF),
            Map.entry("yellow", 0xFFFF00),
            Map.entry("lime", 0x80FF00),
            Map.entry("pink", 0xFF80C0),
            Map.entry("gray", 0x808080),
            Map.entry("grey", 0x808080),
            Map.entry("light_gray", 0xC0C0C0),
            Map.entry("light_grey", 0xC0C0C0),
            Map.entry("cyan", 0x00E5FF),
            Map.entry("purple", 0xA000FF),
            Map.entry("blue", 0x2040FF),
            Map.entry("brown", 0x8B5A2B),
            Map.entry("green", 0x00FF40),
            Map.entry("red", 0xFF2020),
            Map.entry("black", 0x101010));

    /** Markers created by this run of the server. */
    private static final Set<UUID> OURS = ConcurrentHashMap.newKeySet();

    /** Settings already complained about, so a bad value is not logged on every marker. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private BlockBeacon() {
    }

    /** True when this marker was made by the current run and must not be swept. */
    public static boolean isOurs(UUID id) {
        return id != null && OURS.contains(id);
    }

    /**
     * True when this entity is one of the mod's own block markers.
     *
     * A marker is a real entity, so an unfiltered search finds it and reports the mod's own
     * decoration back as a result. Every command that walks the entity list skips these.
     */
    public static boolean isMarker(Entity entity) {
        return entity != null
                && entity.getType() == EntityType.BLOCK_DISPLAY
                && entity.getTags().contains(TAG);
    }

    /**
     * Put a glowing outline on {@code pos}, drawn as the block that is there at its true size.
     *
     * @return the marker's uuid, or {@code null} if it could not be created
     */
    public static UUID show(ServerLevel world, BlockPos pos) {
        Entity marker = EntityType.BLOCK_DISPLAY.create(world);
        if (marker == null) {
            return null;
        }

        marker.moveTo(pos.getX(), pos.getY(), pos.getZ(), 0.0f, 0.0f);

        CompoundTag nbt = marker.saveWithoutId(new CompoundTag());
        nbt.put("block_state", NbtUtils.writeBlockState(displayState()));
        nbt.put("transformation", transformation());
        nbt.putInt("glow_color_override", glowColour());
        // Drawn at full brightness so the outline is as clear at the bottom of an unlit cave as it
        // is on the surface, which is where it is needed most.
        CompoundTag brightness = new CompoundTag();
        brightness.putInt("block", 15);
        brightness.putInt("sky", 15);
        nbt.put("brightness", brightness);
        marker.load(nbt);

        marker.setGlowingTag(true);
        marker.addTag(TAG);

        OURS.add(marker.getUUID());
        if (!world.addFreshEntity(marker)) {
            OURS.remove(marker.getUUID());
            return null;
        }
        return marker.getUUID();
    }

    /** Scale and offset, as the {@code transformation} compound a display entity expects. */
    private static CompoundTag transformation() {
        CompoundTag transformation = new CompoundTag();
        transformation.put("translation", floats(OFFSET, OFFSET, OFFSET));
        transformation.put("scale", floats(SCALE, SCALE, SCALE));
        transformation.put("left_rotation", floats(0.0f, 0.0f, 0.0f, 1.0f));
        transformation.put("right_rotation", floats(0.0f, 0.0f, 0.0f, 1.0f));
        return transformation;
    }

    private static ListTag floats(float... values) {
        ListTag list = new ListTag();
        for (float value : values) {
            list.add(FloatTag.valueOf(value));
        }
        return list;
    }

    /** What the marker draws. */
    private static BlockState displayState() {
        String id = LPConfig.get().blockMarkerFill();
        ResourceLocation parsed = ResourceLocation.tryParse(id);
        if (parsed == null) {
            return fallback(id, "is not a valid block id");
        }
        if (!BuiltInRegistries.BLOCK.containsKey(parsed)) {
            return fallback(id, "is not a block in this game");
        }

        BlockState state = BuiltInRegistries.BLOCK.get(parsed).defaultBlockState();
        if (state.getRenderShape() != RenderShape.MODEL) {
            return fallback(id, "has no model, so there is nothing for the glow to outline");
        }
        return state;
    }

    /**
     * Outline colour from the config, as packed RGB.
     *
     * Accepts a hex code with or without a leading hash, and the sixteen dye names. Anything else
     * falls back to the default and says so once.
     */
    private static int glowColour() {
        String raw = LPConfig.get().blockMarkerColour();
        if (raw == null) {
            return DEFAULT_GLOW_COLOUR;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);

        Integer named = NAMED_COLOURS.get(value.replace(' ', '_'));
        if (named != null) {
            return named;
        }

        String hex = value.startsWith("#") ? value.substring(1) : value;
        if (hex.startsWith("0x")) {
            hex = hex.substring(2);
        }
        if (hex.length() == 6) {
            try {
                return Integer.parseInt(hex, 16);
            } catch (NumberFormatException ignored) {
                // reported below
            }
        }

        if (WARNED.add(raw)) {
            LPLog.info("block_marker_colour " + raw + " is not a colour, expected a hex code such "
                    + "as #00E5FF or a dye name, using " + LPConstants.BLOCK_MARKER_COLOUR
                    + " instead");
        }
        return DEFAULT_GLOW_COLOUR;
    }

    private static BlockState fallback(String id, String why) {
        if (WARNED.add(id)) {
            LPLog.info("block_marker_fill " + id + " " + why + ", using "
                    + LPConstants.BLOCK_MARKER_FILL + " instead");
        }
        return Blocks.GLASS.defaultBlockState();
    }

    /**
     * Remove one marker.
     *
     * @return true when the marker was removed, false when it could not be reached
     */
    public static boolean hide(ServerLevel world, UUID id) {
        if (id == null) {
            return true;
        }
        Entity entity = world.getEntity(id);
        if (entity == null) {
            // Almost always means the chunk is not loaded, so the entity is real but out of
            // reach. Saying so lets the caller keep the marker on its books and try again,
            // instead of forgetting it and leaving something in the world nothing owns.
            return false;
        }
        if (entity.getTags().contains(TAG)) {
            entity.discard();
        }
        OURS.remove(id);
        return true;
    }

    /**
     * Remove every marker in one world.
     *
     * @return how many were removed
     */
    public static int hideAll(ServerLevel world) {
        List<? extends Entity> found = world.getEntities(
                EntityType.BLOCK_DISPLAY, entity -> entity.getTags().contains(TAG));
        for (Entity entity : found) {
            OURS.remove(entity.getUUID());
            entity.discard();
        }
        return found.size();
    }

    /**
     * Discard every marker in this world that is not in {@code keep}.
     *
     * Only loaded chunks can be examined, which is the right scope: an unloaded marker harms
     * nothing and will be checked the moment its chunk comes back.
     *
     * @return how many were removed
     */
    public static int discardUntracked(ServerLevel world, Set<UUID> keep) {
        List<? extends Entity> found = world.getEntities(
                EntityType.BLOCK_DISPLAY,
                entity -> entity.getTags().contains(TAG)
                        && !keep.contains(entity.getUUID()));
        for (Entity entity : found) {
            OURS.remove(entity.getUUID());
            entity.discard();
        }
        return found.size();
    }

    /**
     * Remove markers left in any already-loaded chunk by a previous run.
     *
     * @return how many were removed
     */
    public static int sweep(MinecraftServer server) {
        int removed = 0;
        for (ServerLevel world : server.getAllLevels()) {
            removed += hideAll(world);
        }
        return removed;
    }
}

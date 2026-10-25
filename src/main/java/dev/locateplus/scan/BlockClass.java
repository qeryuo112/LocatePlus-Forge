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

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Sorts a block type into one of three groups, so an export can decide what to write. */
public final class BlockClass {

    /** The kinds of block an export can be asked to list, each with its own switch. */
    public enum Group {

        /** Anything from another mod, whatever kind of block it is. */
        MODDED,
        /** Vanilla blocks a player could have placed. */
        PLACED,
        /** Generated but scarce: ores, spawners, amethyst, ancient debris. */
        NOTABLE,
        /** Ground fill: stone, dirt, water, leaves, bedrock. */
        COMMON
    }

    /** Ground fill, by vanilla tag. */
    private static final List<String> COMMON_TAGS = List.of(
            "minecraft:base_stone_overworld", "minecraft:base_stone_nether", "minecraft:dirt",
            "minecraft:sand", "minecraft:leaves", "minecraft:logs", "minecraft:flowers",
            "minecraft:saplings", "minecraft:crops", "minecraft:coral_blocks",
            "minecraft:corals", "minecraft:wall_corals", "minecraft:snow", "minecraft:ice",
            "minecraft:terracotta", "minecraft:cave_vines",
            // Cross-mod conventions, so a modded stone is recognised as ground rather than treated
            // as something a player built.
            "c:stones", "c:stone", "c:cobblestones", "c:sandstone_blocks", "c:sand",
            "c:gravel", "c:dirt", "c:end_stones", "c:netherrack",
            "forge:stone", "forge:cobblestone", "forge:sandstone", "forge:sand",
            "forge:gravel", "forge:dirt", "forge:end_stones", "forge:netherrack");

    /** Scarce generated blocks, by tag. Ore tags cover stone and deepslate variants at once. */
    private static final List<String> NOTABLE_TAGS = List.of(
            "minecraft:coal_ores", "minecraft:iron_ores", "minecraft:gold_ores",
            "minecraft:copper_ores", "minecraft:diamond_ores", "minecraft:redstone_ores",
            "minecraft:lapis_ores", "minecraft:emerald_ores",
            // The umbrella ore tags nearly every mod adds its ores to.
            "c:ores", "c:ores_in_ground/stone", "c:ores_in_ground/deepslate",
            "c:ores_in_ground/netherrack", "c:raw_blocks", "c:raw_ores",
            "forge:ores", "forge:ores_in_ground/stone", "forge:ores_in_ground/deepslate",
            "forge:ores_in_ground/netherrack", "forge:storage_blocks/raw_iron",
            "forge:storage_blocks/raw_copper", "forge:storage_blocks/raw_gold");

    /** Scarce generated blocks with no ore tag covering them. */
    private static final List<String> NOTABLE_IDS = List.of(
            "minecraft:ancient_debris", "minecraft:nether_gold_ore",
            "minecraft:nether_quartz_ore", "minecraft:gilded_blackstone",
            "minecraft:spawner", "minecraft:budding_amethyst", "minecraft:amethyst_cluster",
            "minecraft:amethyst_block",
            // A geode grows through four stages and only the last is called a cluster.
            "minecraft:small_amethyst_bud", "minecraft:medium_amethyst_bud",
            "minecraft:large_amethyst_bud",
            // Raw metal blocks generate in ore veins in the deepslate layer.
            "minecraft:raw_iron_block", "minecraft:raw_copper_block", "minecraft:raw_gold_block",
            "minecraft:sculk_catalyst", "minecraft:sculk_shrieker", "minecraft:sculk_sensor",
            "minecraft:reinforced_deepslate", "minecraft:dragon_egg",
            "minecraft:end_portal_frame", "minecraft:suspicious_sand",
            "minecraft:infested_stone",
            "minecraft:infested_deepslate", "minecraft:bee_nest", "minecraft:mossy_cobblestone");

    /** Ground fill with no tag that covers it. */
    private static final List<String> COMMON_IDS = List.of(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
            // Formed by magma and soul sand under water, never placed.
            "minecraft:bubble_column",
            // Fire and portals appear on their own; nether portal blocks are the product of a build
            // rather than part of it.
            "minecraft:fire", "minecraft:soul_fire", "minecraft:nether_portal",
            "minecraft:end_portal", "minecraft:end_gateway",
            "minecraft:water", "minecraft:lava", "minecraft:bedrock",
            "minecraft:gravel", "minecraft:clay", "minecraft:netherrack",
            "minecraft:end_stone", "minecraft:obsidian", "minecraft:crying_obsidian",
            "minecraft:magma_block", "minecraft:soul_sand", "minecraft:soul_soil",
            "minecraft:basalt", "minecraft:smooth_basalt", "minecraft:blackstone",
            "minecraft:shroomlight", "minecraft:nether_wart_block",
            "minecraft:warped_wart_block", "minecraft:warped_nylium", "minecraft:crimson_nylium",
            "minecraft:crimson_stem", "minecraft:warped_stem", "minecraft:crimson_fungus",
            "minecraft:warped_fungus", "minecraft:crimson_roots", "minecraft:warped_roots",
            "minecraft:weeping_vines", "minecraft:weeping_vines_plant",
            "minecraft:twisting_vines", "minecraft:twisting_vines_plant",
            "minecraft:grass", "minecraft:tall_grass", "minecraft:fern", "minecraft:large_fern",
            "minecraft:dead_bush", "minecraft:vine", "minecraft:glow_lichen",
            "minecraft:hanging_roots", "minecraft:rooted_dirt", "minecraft:mud",
            "minecraft:moss_block", "minecraft:moss_carpet", "minecraft:mycelium",
            "minecraft:podzol", "minecraft:powder_snow", "minecraft:snow",
            "minecraft:packed_ice", "minecraft:blue_ice", "minecraft:frosted_ice",
            "minecraft:seagrass", "minecraft:tall_seagrass", "minecraft:kelp",
            "minecraft:kelp_plant", "minecraft:sea_pickle", "minecraft:lily_pad",
            "minecraft:sugar_cane", "minecraft:cactus", "minecraft:bamboo",
            "minecraft:sandstone", "minecraft:red_sandstone", "minecraft:tuff",
            "minecraft:calcite", "minecraft:dripstone_block", "minecraft:pointed_dripstone",
            "minecraft:sculk", "minecraft:sculk_vein",
            "minecraft:cobweb", "minecraft:mushroom_stem",
            "minecraft:brown_mushroom", "minecraft:red_mushroom",
            "minecraft:brown_mushroom_block", "minecraft:red_mushroom_block");

    /** Answers are per type, so the tag lookups happen once rather than once per block. */
    private static final Map<ResourceLocation, Group> CACHE = new HashMap<>(256);

    private BlockClass() {
    }

    /** Which group this block type falls into. */
    public static Group of(ResourceLocation id) {
        Group known = CACHE.get(id);
        if (known != null) {
            return known;
        }
        Group group = compute(id);
        CACHE.put(id, group);
        return group;
    }

    /** Which of the four export switches governs this block. */
    public static Group category(ResourceLocation id) {
        return isVanilla(id) ? of(id) : Group.MODDED;
    }

    /** Whether this block came from the base game rather than from a mod. */
    private static boolean isVanilla(ResourceLocation id) {
        return "minecraft".equals(id.getNamespace());
    }

    private static Group compute(ResourceLocation id) {
        String full = id.toString();
        if (NOTABLE_IDS.contains(full)) {
            return Group.NOTABLE;
        }

        BlockState state;
        try {
            state = BuiltInRegistries.BLOCK.get(id).defaultBlockState();
        } catch (Throwable t) {
            return Group.PLACED; // unknown means keep the coordinates, the safer way to be wrong
        }

        for (String tag : NOTABLE_TAGS) {
            if (inTag(state, tag)) {
                return Group.NOTABLE;
            }
        }
        if (COMMON_IDS.contains(full)) {
            return Group.COMMON;
        }
        for (String tag : COMMON_TAGS) {
            if (inTag(state, tag)) {
                return Group.COMMON;
            }
        }
        return Group.PLACED;
    }

    /** Whether a state carries a tag, given as {@code namespace:path}. */
    private static boolean inTag(BlockState state, String tagId) {
        try {
            ResourceLocation id = ResourceLocation.tryParse(tagId);
            return id != null && state.is(TagKey.create(Registries.BLOCK, id));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Forget cached answers. Tags are rebuilt when a datapack reloads. */
    public static void invalidate() {
        CACHE.clear();
    }
}

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

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

/** Resolves the {@code <block_id|#tag>} argument into a state predicate. */
public final class BlockMatcher {

    private static final DynamicCommandExceptionType UNKNOWN_BLOCK = new DynamicCommandExceptionType(
            id -> Component.literal("Unknown block '" + id + "'. Use an id like minecraft:diamond_ore "
                    + "or a tag like #minecraft:logs."));

    private static final DynamicCommandExceptionType UNKNOWN_TAG = new DynamicCommandExceptionType(
            id -> Component.literal("Unknown block tag '#" + id + "'."));

    private final String label;
    private final Predicate<BlockState> predicate;

    /**
     * The single block a plain id matches, or null for a tag or a compound test.
     *
     * A scan asks this question once per block, four million times over a modest radius, so the
     * common cases answer it with a reference comparison rather than through a lambda wrapped in
     * a try/catch.
     */
    private final Block exactBlock;

    /** The tag a {@code #tag} argument matches, or null when this is not a tag. */
    private final TagKey<Block> exactTag;

    /** True for the match-everything form used by {@code /lp analyze blocks}. */
    private final boolean anyNonAir;

    private BlockMatcher(String label, Predicate<BlockState> predicate) {
        this(label, predicate, null, null, false);
    }

    private BlockMatcher(String label, Predicate<BlockState> predicate, Block exactBlock,
                         TagKey<Block> exactTag, boolean anyNonAir) {
        this.label = label;
        this.predicate = predicate;
        this.exactBlock = exactBlock;
        this.exactTag = exactTag;
        this.anyNonAir = anyNonAir;
    }

    public String label() {
        return label;
    }

    public boolean matches(BlockState state) {
        // Three fast paths covering every use the mod itself makes. Only a matcher built from
        // something unusual falls through to the guarded predicate.
        if (exactBlock != null) {
            return state.is(exactBlock);
        }
        if (anyNonAir) {
            return !state.isAir();
        }
        if (exactTag != null) {
            return state.is(exactTag);
        }
        try {
            return predicate.test(state);
        } catch (Throwable t) {
            return false;
        }
    }


    public static BlockMatcher parse(String raw) throws CommandSyntaxException {
        String text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            throw UNKNOWN_BLOCK.create("");
        }

        if (text.charAt(0) == '#') {
            String idText = text.substring(1);
            ResourceLocation id = ResourceLocation.tryParse(idText);
            if (id == null) {
                throw UNKNOWN_TAG.create(idText);
            }
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, id);
            if (BuiltInRegistries.BLOCK.getTag(tag).isEmpty()) {
                throw UNKNOWN_TAG.create(idText);
            }
            return ofTag(tag);
        }

        ResourceLocation id = ResourceLocation.tryParse(text);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            throw UNKNOWN_BLOCK.create(text);
        }
        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(id);
        if (block.isEmpty()) {
            throw UNKNOWN_BLOCK.create(text);
        }
        return ofBlock(id.toString(), block.get());
    }

    /** Matches exactly one block, built from a vanilla registry lookup. */
    public static BlockMatcher ofBlock(String label, Block block) {
        return new BlockMatcher(label, state -> state.is(block), block, null, false);
    }

    /** Matches any block in a tag, built from a vanilla registry lookup. */
    public static BlockMatcher ofTag(TagKey<Block> tag) {
        return new BlockMatcher("#" + tag.location(), state -> state.is(tag), null, tag, false);
    }

    /** Matches every non-air block. Used by {@code /lp analyze blocks}. */
    public static BlockMatcher allNonAir() {
        return new BlockMatcher("all blocks", state -> !state.isAir(), null, null, true);
    }
}

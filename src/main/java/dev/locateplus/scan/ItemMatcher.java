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
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Locale;
import java.util.function.Predicate;

/** Resolves the {@code <item_id|#tag>} argument of {@code /locate item} into a stack predicate. */
public final class ItemMatcher {

    private static final DynamicCommandExceptionType UNKNOWN_ITEM = new DynamicCommandExceptionType(
            id -> Component.literal("Unknown item '" + id + "'. Use an id like minecraft:diamond "
                    + "or a tag like #minecraft:swords."));

    private static final DynamicCommandExceptionType UNKNOWN_TAG = new DynamicCommandExceptionType(
            id -> Component.literal("Unknown item tag '#" + id + "'."));

    private final String label;
    private final Predicate<ItemStack> predicate;

    private ItemMatcher(String label, Predicate<ItemStack> predicate) {
        this.label = label;
        this.predicate = predicate;
    }

    public String label() {
        return label;
    }

    public boolean matches(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            return predicate.test(stack);
        } catch (Throwable t) {
            // A modded item with an unusual implementation must not abort a whole scan.
            return false;
        }
    }

    /** Matches exactly one item, built from a vanilla registry lookup. */
    public static ItemMatcher ofItem(String label, Item item) {
        return new ItemMatcher(label, stack -> stack.is(item));
    }

    /** Matches any item in a tag, built from a vanilla registry lookup. */
    public static ItemMatcher ofTag(TagKey<Item> tag) {
        return new ItemMatcher("#" + tag.location(), stack -> stack.is(tag));
    }


    /**
     * Parse a raw {@code id} or {@code #tag} string.
     *
     * Kept for callers that hold text rather than a resolved registry predicate, and so the
     * validation rules live in exactly one place.
     */
    public static ItemMatcher parse(String raw) throws CommandSyntaxException {
        String text = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            throw UNKNOWN_ITEM.create("");
        }

        if (text.charAt(0) == '#') {
            String idText = text.substring(1);
            ResourceLocation id = ResourceLocation.tryParse(idText);
            if (id == null) {
                throw UNKNOWN_TAG.create(idText);
            }
            TagKey<Item> tag = TagKey.create(Registries.ITEM, id);
            if (BuiltInRegistries.ITEM.getTag(tag).isEmpty()) {
                throw UNKNOWN_TAG.create(idText);
            }
            return ofTag(tag);
        }

        ResourceLocation id = ResourceLocation.tryParse(text);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            throw UNKNOWN_ITEM.create(text);
        }
        Item item = BuiltInRegistries.ITEM.get(id);
        return ofItem(id.toString(), item);
    }
}

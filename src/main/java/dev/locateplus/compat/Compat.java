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
package dev.locateplus.compat;

import dev.locateplus.report.Msg;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * The handful of calls that read item and block entity data.
 *
 * Upstream this file carried a second branch for 1.20.5, where item NBT became data components and
 * a custom name, a shulker box's contents and an enchantment were all reached differently. This
 * port only targets 1.20.1, so the NBT branch is the only one left, but the wrappers stay: they are
 * what keeps the version-specific types out of the rest of the mod.
 */
public final class Compat {

    private Compat() {
    }

    /** Whether the stack carries a name a player gave it. */
    public static boolean hasCustomName(ItemStack stack) {
        return stack.hasCustomHoverName();
    }

    /**
     * Contents of a container held as an item, a shulker box being the obvious case.
     *
     * @return the non-empty stacks inside, or an empty list when this is not a container
     */
    public static List<ItemStack> nestedItems(ItemStack container) {
        if (!(container.getItem() instanceof BlockItem)) {
            return List.of();
        }
        CompoundTag nbt = container.getTag();
        if (nbt == null || !nbt.contains("BlockEntityTag", Tag.TAG_COMPOUND)) {
            return List.of();
        }
        CompoundTag blockTag = nbt.getCompound("BlockEntityTag");
        if (!blockTag.contains("Items", Tag.TAG_LIST)) {
            return List.of();
        }
        ListTag items = blockTag.getList("Items", Tag.TAG_COMPOUND);
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            ItemStack inner;
            try {
                inner = ItemStack.of(items.getCompound(i));
            } catch (Throwable t) {
                continue;
            }
            if (inner != null && !inner.isEmpty()) {
                out.add(inner);
            }
        }
        return out;
    }

    /** Saved data of a block entity, used to read machine progress. */
    public static CompoundTag blockEntityNbt(BlockEntity be, Level world) {
        return be.saveWithoutMetadata();
    }

    /** Whether the stack has any enchantment on it. */
    public static boolean hasEnchantments(ItemStack stack) {
        return stack.isEnchanted();
    }

    /**
     * Enchantments on a stack as {@code "sharpness 5"} lines.
     *
     * Returned already formatted, because the caller only ever wants text: 1.20.1 hands back a
     * plain map of enchantment objects, which have to be looked up in the registry for a name.
     */
    public static List<String> enchantmentLines(ItemStack stack) {
        List<String> out = new ArrayList<>();
        EnchantmentHelper.getEnchantments(stack).forEach((enchantment, level) -> {
            ResourceLocation id = BuiltInRegistries.ENCHANTMENT.getKey(enchantment);
            out.add((id == null ? enchantment.toString() : Msg.words(id.getPath())) + " " + level);
        });
        return out;
    }

    /** Registry id of a status effect, or null when it has none. */
    public static ResourceLocation statusEffectId(MobEffectInstance instance) {
        return BuiltInRegistries.MOB_EFFECT.getKey(instance.getEffect());
    }

    /** Whether a status effect is a good one, which decides the colour it is printed in. */
    public static boolean statusEffectBeneficial(MobEffectInstance instance) {
        return instance.getEffect().isBeneficial();
    }
}

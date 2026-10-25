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
package dev.locateplus.inspect;

import dev.locateplus.compat.Compat;
import dev.locateplus.core.LPConfig;
import dev.locateplus.report.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Container and machine {@code /lp inspect} sections. */
public final class ContainerInspector {

    /** Items whose presence is worth calling out explicitly in a summary. */
    private static final List<String> VALUABLE = List.of(
            "minecraft:diamond", "minecraft:diamond_block", "minecraft:netherite_ingot",
            "minecraft:netherite_block", "minecraft:netherite_scrap", "minecraft:ancient_debris",
            "minecraft:emerald", "minecraft:emerald_block", "minecraft:gold_block",
            "minecraft:iron_block", "minecraft:enchanted_golden_apple", "minecraft:elytra",
            "minecraft:nether_star", "minecraft:beacon", "minecraft:totem_of_undying",
            "minecraft:dragon_egg", "minecraft:enchanted_book", "minecraft:shulker_box");

    /** How many renamed items to name before summarising the rest as a count. */
    private static final int NAMED_ITEMS_SHOWN = 3;

    private ContainerInspector() {
    }

    /** True when this block entity has an inventory worth summarising. */
    public static boolean isContainer(BlockEntity be) {
        return be instanceof Container;
    }

    /**
     * Item counts, distinct types, fill level and any valuables.
     *
     * Stacks are grouped by item id so a chest of 27 stacks of cobblestone reads as one line, which
     * is the whole point of a summary.
     */
    public static void contents(InspectLine out, BlockEntity be) {
        if (!(be instanceof Container inv)) {
            return;
        }

        int slots = inv.getContainerSize();
        int usedSlots = 0;
        long totalItems = 0;
        Map<ResourceLocation, Long> counts = new LinkedHashMap<>();
        List<String> named = new ArrayList<>();
        List<String> enchanted = new ArrayList<>();

        for (int i = 0; i < slots; i++) {
            ItemStack stack;
            try {
                stack = inv.getItem(i);
            } catch (Throwable t) {
                continue; // a modded inventory with an odd slot count must not abort the report
            }
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            usedSlots++;
            totalItems += stack.getCount();
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            counts.merge(id, (long) stack.getCount(), Long::sum);

            if (Compat.hasCustomName(stack)) {
                named.add(stack.getHoverName().getString() + " (" + id + ")");
            }
            if (stack.isEnchanted()) {
                enchanted.add(Msg.words(id.getPath()));
            }
        }

        out.section("Container");
        if (be instanceof net.minecraft.world.level.block.entity.BaseContainerBlockEntity lockable) {
            try {
                if (lockable.getCustomName() != null) {
                    out.field("Name", lockable.getCustomName().getString());
                }
            } catch (Throwable ignored) {
                // custom name is optional
            }
        }

        out.field("Slots used", usedSlots + " / " + slots
                + String.format(Locale.US, "  (%.0f%% full)",
                slots == 0 ? 0.0 : usedSlots * 100.0 / slots));
        out.field("Total items", String.valueOf(totalItems));
        out.field("Distinct types", String.valueOf(counts.size()));

        if (counts.isEmpty()) {
            out.field("Contents", "empty", ChatFormatting.DARK_GRAY);
            return;
        }

        // Biggest stacks first, that is what people scan for.
        List<Map.Entry<ResourceLocation, Long>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<ResourceLocation, Long>comparingByValue().reversed());

        int shown = Math.min(LPConfig.get().chatTopN(), sorted.size());
        for (int i = 0; i < shown; i++) {
            Map.Entry<ResourceLocation, Long> entry = sorted.get(i);
            boolean valuable = VALUABLE.contains(entry.getKey().toString());
            out.item(entry.getValue() + "x " + entry.getKey(),
                    valuable ? ChatFormatting.AQUA : ChatFormatting.WHITE);
        }
        if (sorted.size() > shown) {
            out.item("... and " + (sorted.size() - shown) + " more types", ChatFormatting.DARK_GRAY);
        }

        List<String> valuablesFound = sorted.stream()
                .map(e -> e.getKey().toString())
                .filter(VALUABLE::contains)
                .toList();
        if (!valuablesFound.isEmpty()) {
            out.field("Valuables", String.join(", ", valuablesFound), ChatFormatting.AQUA);
        }
        if (!enchanted.isEmpty()) {
            out.field("Enchanted items", String.valueOf(enchanted.size()), ChatFormatting.LIGHT_PURPLE);
        }
        if (!named.isEmpty()) {
            // Say how many were left out.
            int shownNames = Math.min(NAMED_ITEMS_SHOWN, named.size());
            String list = String.join(", ", named.subList(0, shownNames));
            if (named.size() > shownNames) {
                list += " (+" + (named.size() - shownNames) + " more)";
            }
            out.field("Renamed items", list);
        }
    }

    /** Cook progress, fuel and burn time. */
    public static void machine(InspectLine out, BlockEntity be) {
        CompoundTag nbt;
        try {
            nbt = Compat.blockEntityNbt(be, be.getLevel());
        } catch (Throwable t) {
            return;
        }

        boolean isFurnace = be instanceof AbstractFurnaceBlockEntity;
        boolean hasProgress = nbt.contains("CookTime") || nbt.contains("BurnTime")
                || nbt.contains("progress") || nbt.contains("Progress");
        if (!isFurnace && !hasProgress && !(be instanceof BrewingStandBlockEntity)) {
            return;
        }

        InspectLine section = new InspectLine();

        int burnTime = nbt.getInt("BurnTime");
        int cookTime = nbt.getInt("CookTime");
        int cookTotal = nbt.getInt("CookTimeTotal");

        if (cookTotal > 0) {
            section.field("Cook progress", cookTime + " / " + cookTotal
                    + String.format(Locale.US, "  (%.0f%%)", cookTime * 100.0 / cookTotal));
        }
        if (burnTime > 0) {
            section.field("Fuel remaining", Msg.count(burnTime, "tick")
                    + String.format(Locale.US, "  (%.1f s)", burnTime / 20.0), ChatFormatting.GOLD);
            section.field("Status", "BURNING", ChatFormatting.GOLD);
        } else if (isFurnace) {
            section.field("Status", "not burning", ChatFormatting.DARK_GRAY);
        }

        // Brewing stands use their own keys.
        if (nbt.contains("BrewTime")) {
            int brew = nbt.getInt("BrewTime");
            section.field("Brew time", Msg.count(brew, "tick")
                    + String.format(Locale.US, "  (%.1f s left)", brew / 20.0));
            if (nbt.contains("Fuel")) {
                section.field("Blaze powder", String.valueOf(nbt.getInt("Fuel")));
            }
        }

        // Generic modded progress keys.
        for (String key : new String[]{"progress", "Progress", "energy", "Energy"}) {
            if (nbt.contains(key)) {
                section.field(key, String.valueOf(nbt.getInt(key)));
            }
        }

        if (!section.isEmpty()) {
            out.section("Machine");
            out.lines().addAll(section.lines());
        }
    }
}

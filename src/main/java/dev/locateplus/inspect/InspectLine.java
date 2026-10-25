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

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/** A collected block of {@code /lp inspect} output. */
public final class InspectLine {

    private final List<Component> lines = new ArrayList<>();

    /** A section heading, e.g. "Redstone". */
    public InspectLine section(String title) {
        lines.add(Component.literal("")
                .append(Component.literal(title).withStyle(ChatFormatting.GOLD)));
        return this;
    }

    /** {@code label: value} with two-space indent. */
    public InspectLine field(String label, String value) {
        lines.add(Component.literal("  ")
                .append(Component.literal(label + ": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(value).withStyle(ChatFormatting.WHITE)));
        return this;
    }

    /** A field whose value carries its own colour, used for warnings and highlights. */
    public InspectLine field(String label, String value, ChatFormatting colour) {
        lines.add(Component.literal("  ")
                .append(Component.literal(label + ": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(value).withStyle(colour)));
        return this;
    }

    /** A deeper-indented continuation line, used for list items. */
    public InspectLine item(String value) {
        lines.add(Component.literal("    ")
                .append(Component.literal(value).withStyle(ChatFormatting.WHITE)));
        return this;
    }

    public InspectLine item(String value, ChatFormatting colour) {
        lines.add(Component.literal("    ")
                .append(Component.literal(value).withStyle(colour)));
        return this;
    }

    /** Pre-built text, for lines that need mixed styling. */
    public InspectLine raw(MutableComponent text) {
        lines.add(text);
        return this;
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public int size() {
        return lines.size();
    }

    public List<Component> lines() {
        return lines;
    }
}

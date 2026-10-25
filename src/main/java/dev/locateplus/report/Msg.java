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
package dev.locateplus.report;

import dev.locateplus.core.LPConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * Every message this mod sends is built here.
 *
 * A status line reads "[LP] text": an aqua tag, then grey, green, yellow or red by severity.
 */
public final class Msg {

    /** Prefix on single-line status messages, so mod output is distinguishable from vanilla. */
    private static final String TAG = "[LP] ";

    private Msg() {
    }

    // ---- status lines -------------------------------------------------------------------------

    /** Neutral progress, e.g. "Searching for ...". */
    public static void info(CommandSourceStack source, String text) {
        source.sendSuccess(() -> tagged(text, ChatFormatting.GRAY), false);
    }

    /** Something finished successfully. */
    public static void success(CommandSourceStack source, String text) {
        source.sendSuccess(() -> tagged(text, ChatFormatting.GREEN), false);
    }

    /** A caution the player should read before continuing. */
    public static void warn(CommandSourceStack source, String text) {
        source.sendSuccess(() -> tagged(text, ChatFormatting.YELLOW), false);
    }

    /** A failure. Uses sendError so it is styled and routed like any vanilla command error. */
    public static void error(CommandSourceStack source, String text) {
        source.sendFailure(tagged(text, ChatFormatting.RED));
    }

    private static MutableComponent tagged(String text, ChatFormatting colour) {
        return Component.literal("")
                .append(Component.literal(TAG).withStyle(ChatFormatting.DARK_AQUA))
                .append(Component.literal(text).withStyle(colour));
    }

    /** Prefix arbitrary text with the mod tag, for messages that need mixed styling. */
    public static MutableComponent prefixed(Component body) {
        return Component.literal("")
                .append(Component.literal(TAG).withStyle(ChatFormatting.DARK_AQUA))
                .append(body);
    }

    // ---- report structure ---------------------------------------------------------------------

    /** A blank spacer line. Used sparingly, to separate a report from the chat above it. */
    public static void blank(CommandSourceStack source) {
        source.sendSuccess(Component::empty, false);
    }

    /** Section title: blank line, then bold aqua. Opens every multi-line report. */
    public static void heading(CommandSourceStack source, String title) {
        blank(source);
        source.sendSuccess(() -> Component.literal(title)
                .withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD), false);
    }

    /** A sub-heading inside a report, e.g. "Redstone" within /lp inspect. */
    public static void section(CommandSourceStack source, String title) {
        source.sendSuccess(() -> Component.literal(title).withStyle(ChatFormatting.GOLD), false);
    }

    /** {@code Label: value}, grey label, white value. */
    public static void field(CommandSourceStack source, String label, String value) {
        source.sendSuccess(() -> fieldText(label, value, ChatFormatting.WHITE), false);
    }

    /** A field whose value needs emphasis, e.g. a warning count in yellow. */
    public static void field(CommandSourceStack source, String label, String value,
                             ChatFormatting colour) {
        source.sendSuccess(() -> fieldText(label, value, colour), false);
    }

    private static MutableComponent fieldText(String label, String value, ChatFormatting colour) {
        return Component.literal("  ")
                .append(Component.literal(label + ": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(value).withStyle(colour));
    }

    /**
     * A closing note that is itself a button: clicking it puts a command in the chat box.
     *
     * The line says what will happen rather than printing the command, because a command with an
     * id and a radius in it is longer than the chat width and wraps onto the line below. The
     * command is on the tooltip for anyone who wants to read it first.
     *
     * Suggesting rather than running, since this sits under a list of results and the player may
     * want to change the radius before sending.
     */
    public static void clickToRun(CommandSourceStack source, String text, String command,
                                  String hover) {
        source.sendSuccess(() -> Component.literal("  ")
                .append(Component.literal(text).withStyle(style -> style
                        .withColor(ChatFormatting.AQUA)
                        .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                                net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND, command))
                        .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                                net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                Component.literal(hover))))), false);
    }

    /** A dim continuation line, for hints and secondary detail. */
    public static void note(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal("  " + text)
                .withStyle(ChatFormatting.DARK_GRAY), false);
    }

    /**
     * A numbered result line: {@code 1. minecraft:stone - 402,101 (49.9%)}.
     *
     * @param share pass a negative value to omit the percentage
     */
    public static MutableComponent result(int rank, String name, long count, double share) {
        MutableComponent line = Component.literal("  ")
                .append(Component.literal(rank + ". ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(name).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(" - ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(number(count)).withStyle(ChatFormatting.AQUA));
        if (share >= 0) {
            line.append(Component.literal("  " + percent(share)).withStyle(ChatFormatting.DARK_GRAY));
        }
        return line;
    }

    /**
     * A whole result on one line: rank, name, count, share, then where it is.
     *
     * @param where trailing detail such as a coordinate, or null for none
     * @param share pass a negative value to omit the percentage
     */
    public static MutableComponent resultLine(int rank, String name, long count, double share,
                                         String where, Component button, String coords) {
        MutableComponent line = Component.literal("  ")
                .append(Component.literal(rank + ". ").withStyle(ChatFormatting.DARK_GRAY))
                .append(shortName(name))
                .append(Component.literal(" ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(number(count)).withStyle(ChatFormatting.AQUA));
        if (share >= 0) {
            line.append(Component.literal(" " + percent(share)).withStyle(ChatFormatting.DARK_GRAY));
        }
        if (where != null) {
            line.append(Component.literal("  " + where).withStyle(ChatFormatting.GRAY));
        }
        if (button != null) {
            line.append(Component.literal(" ")).append(button);
        }
        // After the button rather than before it, so the eye reaches the thing it clicks first
        // and the coordinate is there to read or copy without hovering.
        if (coords != null) {
            line.append(Component.literal("  " + coords).withStyle(ChatFormatting.GRAY));
        }
        return line;
    }

    /** Longest an id may be before it is shortened. */
    private static final int NAME_LIMIT = 26;

    /**
     * A block or entity id trimmed to fit one chat line, with the full value on hover.
     *
     * Nothing is lost: hovering shows the id in full, and it is written out complete in an export.
     */
    public static MutableComponent shortName(String id) {
        String display = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        if (display.length() <= NAME_LIMIT) {
            return Component.literal(display).withStyle(ChatFormatting.WHITE);
        }
        String cut = display.substring(0, NAME_LIMIT - 1) + "\u2026";
        return Component.literal(cut).withStyle(style -> style
                .withColor(ChatFormatting.WHITE)
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent(
                        net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT, Component.literal(id))));
    }

    /** The indented detail line that follows a result, e.g. its nearest coordinate. */
    public static MutableComponent detail(String text) {
        return Component.literal("     ").append(Component.literal(text).withStyle(ChatFormatting.GRAY));
    }

    /**
     * "... and 12 more block types", closes a truncated list.
     *
     * @param noun the plural form, which is pluralised back down when only one item remains so
     *             the line does not read "1 more block types"
     */
    public static void more(CommandSourceStack source, int remaining, String noun) {
        more(source, remaining, noun, null);
    }

    /**
     * "... and 12 more block types", closes a truncated list.
     *
     * @param noun the plural form, pluralised back down when only one item remains
     * @param hint how to see the remainder, or null to say nothing
     */
    public static void more(CommandSourceStack source, int remaining, String noun, String hint) {
        String word = remaining == 1 && noun.endsWith("s")
                ? noun.substring(0, noun.length() - 1)
                : noun;
        source.sendSuccess(() -> Component.literal("  ... and " + number(remaining) + " more " + word)
                .withStyle(ChatFormatting.DARK_GRAY), false);
        if (hint != null) {
            source.sendSuccess(() -> Component.literal("  " + hint)
                    .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC), false);
        }
    }

    // ---- value formatting ---------------------------------------------------------------------

    /** Thousands separators: {@code 402101} becomes {@code 402,101}. */
    public static String number(long value) {
        return String.format(Locale.US, "%,d", value);
    }

    /** {@code 0.4991} becomes {@code 49.9%}. One decimal is enough to compare at a glance. */
    public static String percent(double fraction) {
        return String.format(Locale.US, "%.1f%%", fraction * 100.0);
    }

    /**
     * A registry path as words: {@code chest_minecart} becomes {@code chest minecart}.
     *
     * Ids are printed in the middle of sentences all over the reports, and an underscore in the
     * middle of a sentence reads as a variable name rather than a thing in the world.
     */
    public static String words(String path) {
        return path.replace('_', ' ');
    }

    /** {@code 120 63 -35}, the form players can paste straight into a command. */
    public static String coords(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    public static String coords(Vec3 pos) {
        return String.format(Locale.US, "%.2f %.2f %.2f", pos.x, pos.y, pos.z);
    }

    /**
     * A distance in blocks, e.g. {@code 12.3 blocks}.
     *
     * Spelled out rather than abbreviated: "m" reads as metres, and players think in blocks.
     */
    public static String distance(double blocks) {
        return String.format(Locale.US, "%.1f blocks", blocks);
    }

    /** {@code 1,240 ms} or {@code 3.4 s}, whichever reads better. */
    public static String duration(long millis) {
        if (millis < 1000) {
            return millis + " ms";
        }
        return String.format(Locale.US, "%.1f s", millis / 1000.0);
    }

    /** Pluralise a noun against a count: {@code 1 entity}, {@code 4 entities}. */
    public static String count(long value, String singular, String plural) {
        return number(value) + " " + (value == 1 ? singular : plural);
    }

    /** Convenience for the common regular-plural case. */
    public static String count(long value, String singular) {
        return count(value, singular, singular + "s");
    }

    /** How an area is named in chat, covering the loaded case that has no number. */
    public static String area(dev.locateplus.util.Radius radius) {
        if (radius.isLoadedArea()) {
            return "the loaded area";
        }
        return radius.typedUnit() == dev.locateplus.util.Radius.Unit.CHUNKS
                ? chunkReach(radius.chunks())
                : count(radius.blocks(), "block");
    }

    /** A chunk radius, phrased as the reach it is. */
    public static String chunkReach(int chunks) {
        // Zero reaches nowhere, so naming the distance would read as nothing at all.
        return chunks == 0 ? "this chunk only" : count(chunks, "chunk") + " out";
    }

    /** Reminder of the export directory, shown after a report that could have been exported. */
    public static String exportPath() {
        return "config/" + LPConstants.MOD_ID_PATH + "/exports/";
    }
}

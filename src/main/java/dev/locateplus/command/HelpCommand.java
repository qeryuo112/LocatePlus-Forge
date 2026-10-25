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
package dev.locateplus.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import dev.locateplus.core.ConfigFile;
import dev.locateplus.core.LPConfig;
import dev.locateplus.core.LPConstants;
import dev.locateplus.core.LPLog;
import dev.locateplus.core.LPScheduler;
import dev.locateplus.platform.Services;
import dev.locateplus.report.Msg;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

import static net.minecraft.commands.Commands.literal;

/** {@code /lp} and {@code /lp help}, an in-game index of everything this mod adds. */
public final class HelpCommand {

    private HelpCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LPConfig config = LPConfig.get();

        // The subcommands that run a scan or read the world honour their config switch, the same
        // way the top level commands do. Turning one off has to remove it rather than only hide it
        // from the listing, or an operator who switched it off still has it.
        var root = literal("lp")
                .requires(source -> source.hasPermission(LPConfig.get().permissionLevel()))
                .executes(HelpCommand::help)
                .then(literal("help").executes(HelpCommand::help))
                .then(literal("config").executes(HelpCommand::showConfig))
                .then(literal("stop").executes(HelpCommand::stopScans))
                // Clear stays whatever else is switched off. It is how a player gets rid of a
                // marker or a glow that another command left behind, so removing it would strand
                // whatever is already on screen.
                .then(VisualizeCommand.buildClear())
                .then(literal("reload").executes(HelpCommand::reload));

        if (config.commandAnalyzeChunks()) {
            root.then(AnalyzeChunksCommand.build());
        }
        if (config.commandInspect()) {
            root.then(InspectCommand.build());
        }
        if (config.commandVisualize()) {
            root.then(VisualizeCommand.build());
        }

        dispatcher.register(root);
    }

    /** Re-read the config file without restarting. */
    private static int reload(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String summary = ConfigFile.load();
        Msg.success(source, summary);

        int resent = resendCommandTree(source);
        if (resent > 0) {
            Msg.note(source, "Refreshed the command list for "
                    + Msg.count(resent, "player") + ".");
        }
        Msg.note(source, "Adding or removing a command still needs a server restart.");
        LPLog.info("Config reloaded by " + source.getTextName() + ": " + summary);
        return 1;
    }

    /**
     * Push the command tree to everyone online.
     *
     * @return how many players were updated
     */
    private static int resendCommandTree(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return 0;
        }
        int count = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                server.getCommands().sendCommands(player);
                count++;
            } catch (Throwable t) {
                LPLog.error("Could not refresh the command list for "
                        + player.getGameProfile().getName(), t);
            }
        }
        return count;
    }

    /** Print every setting in force, so what the server loaded can be checked at a glance. */
    private static int showConfig(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        LPConfig config = LPConfig.get();

        Msg.heading(source, "Locate Plus settings");
        Msg.field(source, "Version", Services.platform().modVersion());
        Msg.field(source, "Permission level", String.valueOf(config.permissionLevel()));

        Msg.section(source, "Commands");
        command(source, "/locate block", config.commandLocateBlock());
        command(source, "/locate entity", config.commandLocateEntity());
        command(source, "/locate item", config.commandLocateItem());
        command(source, "/locate biome, structure, poi", config.commandVanillaLocateOverride());
        command(source, "/safetp", config.commandSafeTp());
        command(source, "/glow", config.commandGlow());
        command(source, "/lp inspect", config.commandInspect());
        command(source, "/lp analyze", config.commandAnalyzeChunks());
        command(source, "/lp visualize", config.commandVisualize());

        Msg.section(source, "Search range");
        Msg.field(source, "Default block radius", config.defaultBlockRadius() + " blocks");
        Msg.field(source, "Default chunk radius", config.defaultChunkRadius() + " chunks");
        Msg.field(source, "Largest block radius", Msg.number(config.maxBlockRadius()) + " blocks");
        Msg.field(source, "Largest chunk radius", Msg.number(config.maxChunkRadius()) + " chunks");
        Msg.field(source, "Height searched", "Y " + config.scanMinY() + " to " + config.scanMaxY());

        Msg.section(source, "/locate item looks inside");
        Msg.field(source, "Containers", onOff(config.searchContainers()));
        Msg.field(source, "Dropped items", onOff(config.searchDroppedItems()));
        Msg.field(source, "Mobs and item frames", onOff(config.searchEntityInventories()));
        Msg.field(source, "Player inventories", onOff(config.searchPlayerInventories()));
        Msg.field(source, "Inside shulker boxes", onOff(config.searchInsideShulkerBoxes()));
        Msg.field(source, "Container nesting", config.itemNestingDepth() + " deep");

        Msg.section(source, "Teleporting");
        Msg.field(source, "Button click", config.teleportButtonRuns()
                ? "teleports you immediately"
                : "types the command into chat");
        Msg.field(source, "Block, entity and item results",
                config.resultButtonSafeTeleport() ? "safe teleport" : "exact block");
        Msg.field(source, "Biome, structure and poi results",
                config.biomeButtonSafeTeleport() ? "safe teleport" : "exact block");
        Msg.field(source, "Nowhere safe to land", config.safeTpGoAnyway()
                ? "use vanilla /tp instead"
                : "do not teleport");
        Msg.field(source, "Spots checked per teleport",
                Msg.number(config.safeTpSearchBudget()));

        Msg.section(source, "What you see");
        Msg.field(source, "Outline located blocks", onOff(config.markLocatedBlocks()));
        Msg.field(source, "Glow located entities", onOff(config.glowLocatedEntities()));
        Msg.field(source, "Entity glow lasts", Msg.count(config.glowDurationTicks() / 20, "second"));
        Msg.field(source, "Block outline lasts", config.blockMarkerNeverExpires()
                ? "no limit, set block_marker_seconds"
                : Msg.count(config.blockMarkerTicks() / 20, "second")
                        + ", or until the block changes");
        Msg.field(source, "Outline block", config.blockMarkerFill());
        Msg.field(source, "Outline colour", config.blockMarkerColour());

        Msg.section(source, "Chat and exports");
        Msg.field(source, "Results shown in chat", String.valueOf(config.chatTopN()));
        Msg.field(source, "export_modded_blocks", onOff(config.exportModdedBlocks()));
        Msg.field(source, "export_placed_blocks", onOff(config.exportPlacedBlocks()));
        Msg.field(source, "export_notable_blocks", onOff(config.exportNotableBlocks()));
        Msg.field(source, "export_natural_blocks", onOff(config.exportNaturalBlocks()));
        Msg.field(source, "Most export positions", Msg.number(config.maxExportPositions()));
        Msg.field(source, "Most item results", Msg.number(config.maxItemHits()));

        Msg.section(source, "Performance");
        Msg.field(source, "Scan budget", (config.tickBudgetNanos() / 1_000_000L) + " ms per tick");
        Msg.field(source, "Forceload warning", "over "
                + Msg.number(config.forceloadWarnThreshold()) + " chunks");

        Msg.blank(source);
        Msg.note(source, "Edit config/locate-plus/config.json, then run /lp reload.");
        return 1;
    }

    /** Stop every scan this mod has running. */
    private static int stopScans(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();

        List<String> running = LPScheduler.describeRunning();
        if (running.isEmpty()) {
            Msg.note(source, "Nothing is running.");
            return 0;
        }

        // Name them before they are gone, so it is clear what was stopped.
        Msg.warn(source, "Stopping " + Msg.count(running.size(), "scan") + ":");
        for (String description : running) {
            Msg.note(source, description);
        }

        int stopped = LPScheduler.cancelAll();
        Msg.success(source, "Stopped. Any chunks held open by a scan have been released.");
        LPLog.info(source.getTextName() + " stopped " + stopped
                + (stopped == 1 ? " running scan" : " running scans"));
        return stopped;
    }

    /** One command's on/off state, coloured so a disabled one is obvious at a glance. */
    private static void command(CommandSourceStack source, String name, boolean enabled) {
        Msg.field(source, name, enabled ? "on" : "off",
                enabled ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private static String onOff(boolean value) {
        return value ? "yes" : "no";
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        LPConfig config = LPConfig.get();

        Msg.heading(source, LPConstants.MOD_NAME + " " + Services.platform().modVersion());

        Msg.section(source, "Find");
        if (config.commandLocateBlock()) {
            entry(source, "/locate block <id>", "nearest block, and marks it",
                    "/locate block minecraft:diamond_ore 128 blocks");
        }
        if (config.commandLocateEntity()) {
            entry(source, "/locate entity <id>", "nearest entity, and makes it glow",
                    "/locate entity minecraft:zombie 64 blocks");
        }
        if (config.commandLocateItem()) {
            entry(source, "/locate item <id>", "chests, ground, mobs and players",
                    "/locate item minecraft:diamond 128 blocks");
        }
        if (config.commandVanillaLocateOverride()) {
            entry(source, "/locate biome|structure|poi <id>", "vanilla, with a teleport button",
                    "/locate biome minecraft:jungle");
        }

        Msg.section(source, "Survey");
        if (config.commandAnalyzeChunks()) {
            entry(source, "/lp analyze blocks|entities|both <n> chunks",
                    "count everything nearby", "/lp analyze both 4 chunks export");
        }
        if (config.commandInspect()) {
            entry(source, "/lp inspect <x> <y> <z>", "everything about one position",
                    "/lp inspect ~ ~-1 ~");
        }
        if (config.commandVisualize()) {
            entry(source, "/lp visualize <n> chunks [seconds]",
                    "outline the area a scan would cover",
                    "/lp visualize 32 chunks 300");
        }

        Msg.section(source, "Act");
        if (config.commandSafeTp()) {
            entry(source, "/safetp <x> <y> <z>", "teleport somewhere you can stand",
                    "/safetp ~ ~ ~10");
        }
        if (config.commandGlow()) {
            entry(source, "/glow <target> <n> blocks", "outline entities through terrain",
                    "/glow @e[type=minecraft:zombie] 64 blocks");
        }

        Msg.section(source, "Manage");
        entry(source, "/lp stop", "cancel a running scan", "/lp stop");
        entry(source, "/lp clear", "remove glows and markers", "/lp clear");
        entry(source, "/lp config", "show current settings", "/lp config");
        entry(source, "/lp reload", "re-read the config file", "/lp reload");

        blank(source);
        Msg.note(source, "Add a unit to a radius: 64 blocks, or 4 chunks.");
        Msg.note(source, "Add forceload to read chunks that are not loaded.");
        Msg.note(source, "Add export to write the full results to a file.");
        return 1;
    }

    /**
     * One command: usage in white, purpose in grey, laid out like a field in {@code /lp config}.
     * Clicking the line puts the example in the chat box.
     */
    private static void entry(CommandSourceStack source, String usage, String purpose,
                              String example) {
        MutableComponent line = Component.literal("  ")
                .append(Component.literal(usage).withStyle(ChatFormatting.WHITE))
                .append(Component.literal("  " + purpose).withStyle(ChatFormatting.GRAY));
        source.sendSuccess(() -> line.withStyle(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, example))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal(example).withStyle(ChatFormatting.YELLOW)))), false);
    }

    private static void note(CommandSourceStack source, String text) {
        source.sendSuccess(() -> Component.literal("  - " + text)
                .withStyle(ChatFormatting.GRAY), false);
    }

    private static void blank(CommandSourceStack source) {
        source.sendSuccess(Component::empty, false);
    }
}

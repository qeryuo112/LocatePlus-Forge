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
package dev.locateplus.core;

import dev.locateplus.scan.BlockClass;

/**
 * Every setting a server owner can change, and the values in force right now.
 *
 * Defaults live in {@link LPConstants} so there is exactly one place to read them. This class holds
 * the values actually applied, which are the defaults until {@link ConfigFile} replaces them with
 * whatever the file says.
 */
public final class LPConfig {

    private static volatile LPConfig active = new LPConfig();

    // ---- access -------------------------------------------------------------------------------

    /** The settings in force. Never null. */
    public static LPConfig get() {
        return active;
    }

    /** Install a freshly parsed config. Called by {@link ConfigFile} only. */
    static void install(LPConfig config) {
        active = config == null ? new LPConfig() : config;
    }

    // ---- values -------------------------------------------------------------------------------

    private int permissionLevel = LPConstants.DEFAULT_PERMISSION_LEVEL;

    private int defaultBlockRadius = LPConstants.DEFAULT_BLOCK_RADIUS;
    private int defaultChunkRadius = LPConstants.DEFAULT_CHUNK_RADIUS;

    private int glowDurationSeconds = LPConstants.GLOW_DURATION_TICKS / 20;
    private int blockMarkerSeconds = LPConstants.BLOCK_MARKER_SECONDS;
    private String blockMarkerFill = LPConstants.BLOCK_MARKER_FILL;
    private String blockMarkerColour = LPConstants.BLOCK_MARKER_COLOUR;

    private int scanTimeBudgetMillis = (int) (LPConstants.TICK_BUDGET_NANOS / 1_000_000L);
    private int forceloadWarnThreshold = LPConstants.FORCELOAD_WARN_THRESHOLD;
    private int maxExportPositions = LPConstants.MAX_EXPORT_POSITIONS;
    private int maxItemHits = LPConstants.MAX_ITEM_HITS;
    private int maxBlockRadius = LPConstants.MAX_BLOCK_RADIUS;
    private int maxChunkRadius = LPConstants.MAX_CHUNK_RADIUS;
    private int itemNestingDepth = LPConstants.MAX_ITEM_NESTING_DEPTH;
    private String teleportButtonMode = LPConstants.TELEPORT_BUTTON_MODE;
    private boolean resultButtonSafeTeleport = LPConstants.RESULT_BUTTON_SAFE_TELEPORT;
    private boolean biomeButtonSafeTeleport = LPConstants.BIOME_BUTTON_SAFE_TELEPORT;
    private boolean safeTpGoAnyway = LPConstants.SAFETP_GO_ANYWAY;
    private int scanMinY = LPConstants.SCAN_MIN_Y;
    private int scanMaxY = LPConstants.SCAN_MAX_Y;
    private int safeTpSearchBudget = 2_000_000;
    private boolean glowLocatedEntities = true;
    private boolean markLocatedBlocks = LPConstants.MARK_LOCATED_BLOCKS;
    private int chatTopN = LPConstants.CHAT_TOP_N;
    private boolean exportModdedBlocks = true;
    private boolean exportPlacedBlocks = true;
    private boolean exportNotableBlocks = false;
    private boolean exportNaturalBlocks = false;

    private boolean searchContainers = true;
    private boolean searchDroppedItems = true;
    private boolean searchEntityInventories = true;
    private boolean searchPlayerInventories = true;
    private boolean searchInsideShulkerBoxes = true;

    private boolean commandLocateBlock = true;
    private boolean commandLocateEntity = true;
    private boolean commandLocateItem = true;
    private boolean commandVanillaLocateOverride = true;
    private boolean commandInspect = true;
    private boolean commandSafeTp = true;
    private boolean commandGlow = true;
    private boolean commandAnalyzeChunks = true;
    private boolean commandVisualize = true;

    // ---- getters ------------------------------------------------------------------------------

    /**
     * Permission level required by every command this mod adds.
     *
     * 0 lets any player run them, 2 is the vanilla cheat level and the default, 4 is owner.
     */
    public int permissionLevel() {
        return permissionLevel;
    }

    public int defaultBlockRadius() {
        return defaultBlockRadius;
    }

    public int defaultChunkRadius() {
        return defaultChunkRadius;
    }

    /** Longest list any command prints to chat before collapsing the rest into a count. */
    public int chatTopN() {
        return chatTopN;
    }

    /** Whether {@code /locate block} outlines its nearest match. */
    public boolean markLocatedBlocks() {
        return markLocatedBlocks;
    }

    public int glowDurationTicks() {
        return glowDurationSeconds * 20;
    }

    /** How long a glowing block outline lasts, in ticks, or zero for no limit. */
    public int blockMarkerTicks() {
        return blockMarkerSeconds * 20;
    }

    /** Outline colour as written in the config, e.g. {@code #00E5FF}. */
    public String blockMarkerColour() {
        return blockMarkerColour;
    }

    /** Block the glowing marker is drawn as. */
    public String blockMarkerFill() {
        return blockMarkerFill;
    }

    /** Put the block outline timer back to its default. Used when migrating an older file. */
    void restoreBlockMarkerDefault() {
        blockMarkerSeconds = LPConstants.BLOCK_MARKER_SECONDS;
    }

    /** Put the marker block back to its default. Used when migrating an older file. */
    void restoreBlockMarkerFillDefault() {
        blockMarkerFill = LPConstants.BLOCK_MARKER_FILL;
    }

    /** True when block outlines stay until something removes them. */
    public boolean blockMarkerNeverExpires() {
        return blockMarkerSeconds <= 0;
    }

    /** Server-thread nanoseconds per tick that all running scans may share. */
    public long tickBudgetNanos() {
        return scanTimeBudgetMillis * 1_000_000L;
    }

    public int forceloadWarnThreshold() {
        return forceloadWarnThreshold;
    }

    public int maxExportPositions() {
        return maxExportPositions;
    }

    /**
     * Cap on individual item locations one {@code /locate item} scan keeps.
     *
     * Separate from the export budget because an item hit holds a position, a count, a holder
     * description and a source, so it costs far more per entry than the packed long a block
     * position needs.
     */
    public int maxItemHits() {
        return maxItemHits;
    }

    /** Largest block radius any command will accept. */
    public int maxBlockRadius() {
        return maxBlockRadius;
    }

    /** Largest chunk radius any command will accept. See {@link #maxBlockRadius()}. */
    public int maxChunkRadius() {
        return maxChunkRadius;
    }

    /** How deep {@code /locate item} follows a container held inside another container. */
    public int itemNestingDepth() {
        return itemNestingDepth;
    }

    /**
     * What a chat teleport button does: {@code "suggest"} fills the chat box, {@code "run"} goes
     * immediately.
     */
    public String teleportButtonMode() {
        return teleportButtonMode;
    }

    /** Whether a teleport button should move the player the moment it is clicked. */
    public boolean teleportButtonRuns() {
        return "run".equals(teleportButtonMode);
    }

    /** Whether a button on this mod's own results uses {@code /safetp} rather than {@code /tp}. */
    public boolean resultButtonSafeTeleport() {
        return resultButtonSafeTeleport;
    }

    /** Whether a button on a biome, structure or poi result uses {@code /safetp}. */
    public boolean biomeButtonSafeTeleport() {
        return biomeButtonSafeTeleport;
    }

    /**
     * Lowest Y a scan reads.
     *
     * A raw setting: use {@link #scanMinY(net.minecraft.world.World)} so the world's own floor is
     * respected, since a dimension can be shallower than whatever the file asks for.
     */
    public int scanMinY() {
        return scanMinY;
    }

    /** Highest Y a scan reads. See {@link #scanMinY()} for why the world form is preferred. */
    public int scanMaxY() {
        return scanMaxY;
    }

    /**
     * Lowest Y to read in {@code world}, never below what the dimension actually has.
     *
     * The nether floor is 0 and the overworld's is -64, so a single number cannot be right
     * everywhere. Clamping here keeps the setting meaningful in every dimension instead of
     * scanning empty space or missing the bottom.
     */
    public int scanMinY(net.minecraft.world.level.Level world) {
        return clampToWorld(Math.min(scanMinY, scanMaxY), world);
    }

    /** Highest Y to read in {@code world}, never above the dimension's ceiling. */
    public int scanMaxY(net.minecraft.world.level.Level world) {
        return clampToWorld(Math.max(scanMinY, scanMaxY), world);
    }

    /**
     * Pull one end of the scan band inside {@code world}.
     *
     * Both ends go through this, so a band that sits entirely outside the dimension collapses onto
     * the nearest real height rather than crossing over itself. A pair where the low end lands
     * above the high end describes no blocks at all, which reads as a scan that finds nothing in a
     * chunk that plainly has something in it.
     */
    private static int clampToWorld(int y, net.minecraft.world.level.Level world) {
        return Math.max(world.getMinBuildHeight(), Math.min(y, world.getMaxBuildHeight() - 1));
    }

    /** Positions {@code /safetp} may examine before falling back to the surface. */
    public int safeTpSearchBudget() {
        return safeTpSearchBudget;
    }

    /**
     * Whether {@code /safetp} falls back to an ordinary teleport when nowhere safe was found.
     *
     * True moves you to the exact spot asked for, the same as vanilla {@code /tp}, and warns that
     * it was not checked. False cancels the teleport and leaves you where you are.
     */
    public boolean safeTpGoAnyway() {
        return safeTpGoAnyway;
    }

    /** Whether {@code /locate entity} makes its nearest match glow. */
    public boolean glowLocatedEntities() {
        return glowLocatedEntities;
    }

    /** Whether an export lists coordinates for blocks added by other mods. */
    public boolean exportModdedBlocks() {
        return exportModdedBlocks;
    }

    /** Whether an export lists coordinates for vanilla blocks a player could have placed. */
    public boolean exportPlacedBlocks() {
        return exportPlacedBlocks;
    }

    /** Whether an export lists coordinates for ores, spawners and other rare natural finds. */
    public boolean exportNotableBlocks() {
        return exportNotableBlocks;
    }

    /** Whether an export lists coordinates for ordinary terrain: stone, dirt, water. */
    public boolean exportNaturalBlocks() {
        return exportNaturalBlocks;
    }

    /** Whether the switch covering {@code group} is on. */
    public boolean exportsGroup(BlockClass.Group group) {
        return switch (group) {
            case MODDED -> exportModdedBlocks;
            case PLACED -> exportPlacedBlocks;
            case NOTABLE -> exportNotableBlocks;
            case COMMON -> exportNaturalBlocks;
        };
    }

    public boolean searchContainers() {
        return searchContainers;
    }

    public boolean searchDroppedItems() {
        return searchDroppedItems;
    }

    public boolean searchEntityInventories() {
        return searchEntityInventories;
    }

    /**
     * Whether {@code /locate item} looks inside player inventories and ender chests.
     *
     * Separate from the other scopes because it is the one that reads something players may
     * consider private, so a server can switch it off without losing the rest of the command.
     */
    public boolean searchPlayerInventories() {
        return searchPlayerInventories;
    }

    public boolean searchInsideShulkerBoxes() {
        return searchInsideShulkerBoxes;
    }

    public boolean commandLocateBlock() {
        return commandLocateBlock;
    }

    public boolean commandLocateEntity() {
        return commandLocateEntity;
    }

    public boolean commandLocateItem() {
        return commandLocateItem;
    }

    public boolean commandVanillaLocateOverride() {
        return commandVanillaLocateOverride;
    }

    public boolean commandInspect() {
        return commandInspect;
    }

    public boolean commandSafeTp() {
        return commandSafeTp;
    }

    public boolean commandGlow() {
        return commandGlow;
    }

    public boolean commandAnalyzeChunks() {
        return commandAnalyzeChunks;
    }


    /** Whether {@code /lp visualize} is registered. */
    public boolean commandVisualize() {
        return commandVisualize;
    }

    // ---- population ---------------------------------------------------------------------------

    /**
     * Apply one parsed value, clamping it into a range the server can survive.
     *
     * Clamping rather than rejecting keeps a fat-fingered file working: a permission level of 47
     * becomes 4 and the server still starts. {@link ConfigFile} reports every value it had to
     * change, so the edit is not silent.
     *
     * @return a description of the clamp that was applied, or {@code null} if the value was fine
     */
    String apply(String key, Object value) {
        switch (key) {
            case "permission_level": {
                int v = asInt(value, permissionLevel);
                permissionLevel = clamp(v, 0, 4);
                return note(key, v, permissionLevel);
            }
            case "default_block_radius": {
                int v = asInt(value, defaultBlockRadius);
                defaultBlockRadius = clamp(v, 1, LPConstants.MAX_BLOCK_RADIUS);
                return note(key, v, defaultBlockRadius);
            }
            case "default_chunk_radius": {
                int v = asInt(value, defaultChunkRadius);
                defaultChunkRadius = clamp(v, 1, LPConstants.MAX_CHUNK_RADIUS);
                return note(key, v, defaultChunkRadius);
            }
            case "glow_duration_seconds": {
                int v = asInt(value, glowDurationSeconds);
                glowDurationSeconds = clamp(v, 1, 3600);
                return note(key, v, glowDurationSeconds);
            }
            case "block_marker_seconds": {
                int v = asInt(value, blockMarkerSeconds);
                // Zero is meaningful: it means the outline has no time limit at all.
                blockMarkerSeconds = clamp(v, 0, 86_400);
                return note(key, v, blockMarkerSeconds);
            }
            case "block_marker_colour":
            case "block_marker_color": {
                String v = asString(value, blockMarkerColour).trim();
                blockMarkerColour = v.isEmpty() ? LPConstants.BLOCK_MARKER_COLOUR : v;
                return null;
            }
            case "block_marker_fill": {
                String v = asString(value, blockMarkerFill);
                blockMarkerFill = v.isBlank() ? LPConstants.BLOCK_MARKER_FILL : v.trim();
                return null;
            }
            case "glow_located_entities":
                glowLocatedEntities = asBool(value, glowLocatedEntities);
                return null;
            case "mark_located_blocks":
                markLocatedBlocks = asBool(value, markLocatedBlocks);
                return null;
            case "chat_results_shown": {
                int v = asInt(value, chatTopN);
                // One is the nearest match on its own; past a few hundred the report scrolls its
                // own heading out of the client's chat buffer.
                chatTopN = clamp(v, 1, 1_000);
                return note(key, v, chatTopN);
            }
            case "export_modded_blocks":
                exportModdedBlocks = asBool(value, exportModdedBlocks);
                return null;
            case "export_placed_blocks":
                exportPlacedBlocks = asBool(value, exportPlacedBlocks);
                return null;
            case "export_notable_blocks":
                exportNotableBlocks = asBool(value, exportNotableBlocks);
                return null;
            case "export_natural_blocks":
                exportNaturalBlocks = asBool(value, exportNaturalBlocks);
                return null;
            case "scan_time_budget_ms": {
                int v = asInt(value, scanTimeBudgetMillis);
                // A tick is 50 ms. Anything approaching that starves the rest of the server.
                scanTimeBudgetMillis = clamp(v, 1, 40);
                return note(key, v, scanTimeBudgetMillis);
            }
            case "forceload_warn_threshold": {
                int v = asInt(value, forceloadWarnThreshold);
                forceloadWarnThreshold = clamp(v, 1, 1_000_000);
                return note(key, v, forceloadWarnThreshold);
            }
            case "max_export_positions": {
                int v = asInt(value, maxExportPositions);
                maxExportPositions = clamp(v, 1_000, 20_000_000);
                return note(key, v, maxExportPositions);
            }
            case "max_item_hits": {
                int v = asInt(value, maxItemHits);
                maxItemHits = clamp(v, 100, 5_000_000);
                return note(key, v, maxItemHits);
            }
            case "max_block_radius": {
                int v = asInt(value, maxBlockRadius);
                // Bounded by what the command argument itself will parse, not by the shipped
                // default, so raising this actually raises the ceiling.
                maxBlockRadius = clamp(v, 1, dev.locateplus.util.Radius.hardMaxBlocks());
                return note(key, v, maxBlockRadius);
            }
            case "max_chunk_radius": {
                int v = asInt(value, maxChunkRadius);
                // A chunk radius is a reach, so this describes 64,000 blocks the same way the
                // block ceiling does.
                maxChunkRadius = clamp(v, 1, dev.locateplus.util.Radius.hardMaxBlocks() / 16);
                return note(key, v, maxChunkRadius);
            }
            case "safetp_search_budget": {
                int v = asInt(value, safeTpSearchBudget);
                safeTpSearchBudget = clamp(v, 1_000, 50_000_000);
                return note(key, v, safeTpSearchBudget);
            }
            case "fallback_to_vanilla_tp":
            case "safetp_teleport_even_if_unsafe":
            case "safetp_vanilla_fallback":
                safeTpGoAnyway = asBool(value, safeTpGoAnyway);
                return null;
            case "teleport_button_mode": {
                String v = asString(value, teleportButtonMode);
                String cleaned = v == null ? "" : v.trim().toLowerCase(java.util.Locale.ROOT);
                // Anything unrecognised falls back to filling the box, the safer of the two.
                teleportButtonMode = "run".equals(cleaned) ? "run" : "suggest";
                return cleaned.equals(teleportButtonMode) ? null
                        : key + " was '" + v + "', using '" + teleportButtonMode + "'";
            }
            // The old names are still read so a file written by an earlier build keeps working.
            case "safe_teleport_from_results":
            case "teleport_button_safe":
                resultButtonSafeTeleport = asBool(value, resultButtonSafeTeleport);
                return null;
            case "safe_teleport_from_biome_results":
            case "vanilla_teleport_button_safe":
                biomeButtonSafeTeleport = asBool(value, biomeButtonSafeTeleport);
                return null;
            case "scan_min_y": {
                int v = asInt(value, scanMinY);
                scanMinY = clamp(v, -LPConstants.SCAN_Y_BOUND, LPConstants.SCAN_Y_BOUND);
                return note(key, v, scanMinY);
            }
            case "scan_max_y": {
                int v = asInt(value, scanMaxY);
                scanMaxY = clamp(v, -LPConstants.SCAN_Y_BOUND, LPConstants.SCAN_Y_BOUND);
                return note(key, v, scanMaxY);
            }
            case "item_nesting_depth": {
                int v = asInt(value, itemNestingDepth);
                // Bounded because a modded container that can hold itself would otherwise recurse
                // until the stack overflows.
                itemNestingDepth = clamp(v, 1, 16);
                return note(key, v, itemNestingDepth);
            }
            case "search_containers":
                searchContainers = asBool(value, searchContainers);
                return null;
            case "search_dropped_items":
                searchDroppedItems = asBool(value, searchDroppedItems);
                return null;
            case "search_entity_inventories":
                searchEntityInventories = asBool(value, searchEntityInventories);
                return null;
            case "search_player_inventories":
                searchPlayerInventories = asBool(value, searchPlayerInventories);
                return null;
            case "search_inside_shulker_boxes":
                searchInsideShulkerBoxes = asBool(value, searchInsideShulkerBoxes);
                return null;
            case "locate_block":
                commandLocateBlock = asBool(value, commandLocateBlock);
                return null;
            case "locate_entity":
                commandLocateEntity = asBool(value, commandLocateEntity);
                return null;
            case "locate_item":
                commandLocateItem = asBool(value, commandLocateItem);
                return null;
            case "locate_biome_structure_poi":
                commandVanillaLocateOverride = asBool(value, commandVanillaLocateOverride);
                return null;
            case "lp_inspect":
            case "inspect":
                commandInspect = asBool(value, commandInspect);
                return null;
            case "safetp":
                commandSafeTp = asBool(value, commandSafeTp);
                return null;
            case "glow":
                commandGlow = asBool(value, commandGlow);
                return null;
            case "lp_analyze":
            case "analyzechunks":
                commandAnalyzeChunks = asBool(value, commandAnalyzeChunks);
                return null;
            case "lp_visualize":
            case "visualize":
                commandVisualize = asBool(value, commandVisualize);
                return null;
            default:
                return null; // unknown keys are ignored so an old file still loads
        }
    }

    private static String note(String key, int requested, int applied) {
        return requested == applied ? null
                : key + " was " + requested + ", using " + applied;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String asString(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static boolean asBool(Object value, boolean fallback) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof String text) {
            String t = text.trim();
            if (t.equalsIgnoreCase("true")) {
                return true;
            }
            if (t.equalsIgnoreCase("false")) {
                return false;
            }
        }
        return fallback;
    }
}

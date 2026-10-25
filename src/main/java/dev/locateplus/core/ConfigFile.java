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

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.locateplus.platform.Services;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads and writes {@code config/locate-plus/config.json}.
 *
 * Unknown keys are ignored and missing keys keep their default, so a config written by an older
 * version keeps working and a new setting simply appears on the next rewrite.
 */
public final class ConfigFile {

    /** Bumped when a key is renamed or removed, so the file can be migrated rather than reset. */
    private static final int SCHEMA_VERSION = 38;

    /** First schema written after the block outline timer default was corrected. */
    private static final int TIMER_RESTORED_SCHEMA = 26;

    /** First schema written after the marker went back to clear glass. */
    private static final int CLEAR_GLASS_SCHEMA = 27;

    /** The tinted block the marker briefly used. */
    private static final String TINTED_MARKER_FILL = "minecraft:light_gray_stained_glass";

    private static final String FILE_NAME = "config.json";

    private ConfigFile() {
    }

    /** The config file path, whether or not it exists yet. */
    public static Path path() {
        return Services.platform().configDir()
                .resolve(LPConstants.MOD_ID_PATH)
                .resolve(FILE_NAME);
    }

    /**
     * Load the file into {@link LPConfig}, creating it with defaults when absent.
     *
     * @return a short summary suitable for the server log or a chat reply
     */
    public static String load() {
        Path file = path();
        LPConfig fresh = new LPConfig();

        if (!Files.exists(file)) {
            LPConfig.install(fresh);
            try {
                write(fresh);
                return "Wrote a default config to " + display(file);
            } catch (IOException e) {
                LPLog.error("Could not write the default config to " + file, e);
                return "Using defaults; the config file could not be written";
            }
        }

        String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LPLog.error("Could not read " + file, e);
            LPConfig.install(fresh);
            return "Using defaults; the config file could not be read";
        }

        JsonObject root;
        try {
            root = parseLenient(raw);
        } catch (Exception e) {
            LPLog.warn("Config file " + display(file) + " is not valid JSON, using defaults "
                    + "for this run. The file has been left untouched so the mistake can be "
                    + "fixed. Reason: " + e.getMessage());
            LPConfig.install(fresh);
            return "Config file has a syntax error; using defaults";
        }

        List<String> adjusted = new ArrayList<>();
        int fileVersion = readVersion(root);
        int applied = readInto(fresh, root, adjusted);
        LPConfig.install(fresh);

        for (String note : adjusted) {
            LPLog.warn("Config: " + note);
        }

        String summary = "Loaded " + applied + (applied == 1 ? " setting" : " settings")
                + " from " + display(file);
        if (!adjusted.isEmpty()) {
            summary += ", " + adjusted.size() + " out of range and clamped";
        }

        // Block outlines briefly shipped with no timer, so a file from that period carries a zero
        // nobody chose, and it survived every later rewrite because a rewrite preserves values.
        // Any file predating this schema is corrected once; from here on a zero can only have been
        // typed deliberately and is left alone.
        if (fileVersion < TIMER_RESTORED_SCHEMA && fresh.blockMarkerTicks() == 0) {
            fresh.restoreBlockMarkerDefault();
            summary += ", and set the block outline timer back to "
                    + LPConstants.BLOCK_MARKER_SECONDS + " seconds";
        }

        // The marker was briefly made of light grey stained glass, which tints everything behind
        // it. A file from that period carries the tinted block for the same reason it carried the
        // zero above, so it is corrected once. Any other value was chosen and is kept.
        if (fileVersion < CLEAR_GLASS_SCHEMA
                && TINTED_MARKER_FILL.equals(fresh.blockMarkerFill())) {
            fresh.restoreBlockMarkerFillDefault();
            summary += ", and set the block outline back to clear glass";
        }

        // A file written by an older version is missing whatever has been added since.
        if (fileVersion < SCHEMA_VERSION) {
            try {
                write(fresh);
                summary += ", and updated it from version " + fileVersion
                        + " to " + SCHEMA_VERSION;
            } catch (IOException e) {
                LPLog.error("Could not update " + file + " to the current format", e);
            }
        }
        return summary;
    }

    /** The {@code config_version} in a parsed file, or 0 when it predates versioning. */
    private static int readVersion(JsonObject root) {
        try {
            JsonElement element = root.get("config_version");
            if (element != null && element.isJsonPrimitive()
                    && element.getAsJsonPrimitive().isNumber()) {
                return element.getAsInt();
            }
        } catch (Exception ignored) {
            // an unreadable version marker is treated as very old, which is the safe answer
        }
        return 0;
    }

    /**
     * Walk the parsed document and hand every leaf value to {@link LPConfig#apply}.
     *
     * Nested objects are flattened by key alone. Section names exist purely to organise the file
     * for a human reader, so a key stays valid even if the sections are rearranged.
     *
     * @return how many keys were recognised
     */
    private static int readInto(LPConfig config, JsonObject root, List<String> adjusted) {
        int count = 0;
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();

            if (value.isJsonObject()) {
                count += readInto(config, value.getAsJsonObject(), adjusted);
                continue;
            }
            if (!value.isJsonPrimitive()) {
                continue;
            }

            JsonPrimitive primitive = value.getAsJsonPrimitive();
            Object plain;
            if (primitive.isBoolean()) {
                plain = primitive.getAsBoolean();
            } else if (primitive.isNumber()) {
                plain = primitive.getAsNumber();
            } else {
                plain = primitive.getAsString();
            }

            String note = config.apply(key, plain);
            if (note != null) {
                adjusted.add(note);
            }
            count++;
        }
        return count;
    }

    /**
     * Parse with comments allowed.
     *
     * Gson's lenient mode already skips {@code //} and block comments; this wrapper exists so the
     * intent is stated once and every caller gets the same behaviour.
     */
    private static JsonObject parseLenient(String raw) {
        com.google.gson.stream.JsonReader reader =
                new com.google.gson.stream.JsonReader(new StringReader(raw));
        reader.setLenient(true);
        JsonElement parsed = JsonParser.parseReader(reader);
        if (parsed == null || !parsed.isJsonObject()) {
            throw new IllegalArgumentException("the file does not contain a JSON object");
        }
        return parsed.getAsJsonObject();
    }

    // ---- writing ------------------------------------------------------------------------------

    /**
     * Write the file by hand rather than serialising an object.
     *
     * Gson's writer would drop every comment, and the comments are most of the value here.
     */
    public static void write(LPConfig config) throws IOException {
        Path file = path();
        Files.createDirectories(file.getParent());

        try (BufferedWriter out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            out.write("{");
            out.newLine();
            out.write("  \"config_version\": " + SCHEMA_VERSION + ",");
            out.newLine();

            section(out, "PERMISSIONS");
            comment(out, "Who can use these commands: 0 = Everyone, 2 = Admins, 4 = Owner");
            entry(out, "permission_level", config.permissionLevel(), true);

            section(out, "TURN COMMANDS ON OR OFF");
            comment(out, "Change to false to disable a command completely");
            entry(out, "locate_block", config.commandLocateBlock(), true);
            entry(out, "locate_entity", config.commandLocateEntity(), true);
            entry(out, "locate_item", config.commandLocateItem(), true);
            entry(out, "locate_biome_structure_poi", config.commandVanillaLocateOverride(), true);
            entry(out, "safetp", config.commandSafeTp(), true);
            entry(out, "glow", config.commandGlow(), true);
            entry(out, "inspect", config.commandInspect(), true);
            entry(out, "analyzechunks", config.commandAnalyzeChunks(), true);
            entry(out, "visualize", config.commandVisualize(), true);

            section(out, "HOW FAR THE MOD SEARCHES");
            comment(out, "Block distances and height limits");
            entry(out, "default_block_radius", config.defaultBlockRadius(), true);
            entry(out, "default_chunk_radius", config.defaultChunkRadius(), true);
            blank(out);
            entry(out, "max_block_radius", config.maxBlockRadius(), true);
            entry(out, "max_chunk_radius", config.maxChunkRadius(), true);
            blank(out);
            entry(out, "scan_min_y", config.scanMinY(), true);
            entry(out, "scan_max_y", config.scanMaxY(), true);

            section(out, "WHAT TO LOOK INSIDE WHEN FINDING ITEMS");
            entry(out, "search_containers", config.searchContainers(), true);
            entry(out, "search_dropped_items", config.searchDroppedItems(), true);
            entry(out, "search_entity_inventories", config.searchEntityInventories(), true);
            entry(out, "search_player_inventories", config.searchPlayerInventories(), true);
            entry(out, "search_inside_shulker_boxes", config.searchInsideShulkerBoxes(), true);
            entry(out, "item_nesting_depth", config.itemNestingDepth(), true);

            section(out, "TELEPORTING");
            comment(out, "\"suggest\" types the command into chat, \"run\" teleports immediately");
            entry(out, "teleport_button_mode", config.teleportButtonMode(), true);
            entry(out, "safe_teleport_from_results", config.resultButtonSafeTeleport(), true);
            entry(out, "safe_teleport_from_biome_results", config.biomeButtonSafeTeleport(), true);
            entry(out, "fallback_to_vanilla_tp", config.safeTpGoAnyway(), true);
            entry(out, "safetp_search_budget", config.safeTpSearchBudget(), true);

            section(out, "HIGHLIGHTS AND GLOW EFFECTS");
            entry(out, "mark_located_blocks", config.markLocatedBlocks(), true);
            entry(out, "glow_located_entities", config.glowLocatedEntities(), true);
            blank(out);
            entry(out, "glow_duration_seconds", config.glowDurationTicks() / 20, true);
            entry(out, "block_marker_seconds", config.blockMarkerTicks() / 20, true);
            blank(out);
            entry(out, "block_marker_fill", config.blockMarkerFill(), true);
            entry(out, "block_marker_colour", config.blockMarkerColour(), true);

            section(out, "CHAT RESULTS AND EXPORTS");
            entry(out, "chat_results_shown", config.chatTopN(), true);
            blank(out);
            entry(out, "export_modded_blocks", config.exportModdedBlocks(), true);
            entry(out, "export_placed_blocks", config.exportPlacedBlocks(), true);
            entry(out, "export_notable_blocks", config.exportNotableBlocks(), true);
            entry(out, "export_natural_blocks", config.exportNaturalBlocks(), true);
            blank(out);
            entry(out, "max_export_positions", config.maxExportPositions(), true);
            entry(out, "max_item_hits", config.maxItemHits(), true);

            section(out, "PERFORMANCE AND LAG PREVENTION");
            entry(out, "scan_time_budget_ms", (int) (config.tickBudgetNanos() / 1_000_000L), true);
            entry(out, "forceload_warn_threshold", config.forceloadWarnThreshold(), false);

            blank(out);
            out.write("}");
            out.newLine();
        }
    }

    /** A section heading, as {@code // --- NAME ---} with a blank line above it. */
    private static void section(BufferedWriter out, String title) throws IOException {
        // Two blank lines above a heading, so the sections separate at a glance.
        out.newLine();
        out.newLine();
        out.write("  // --- " + title + " ---");
        out.newLine();
    }

    private static void comment(BufferedWriter out, String text) throws IOException {
        out.write("  // " + text);
        out.newLine();
    }

    /** A blank line, used to group related settings inside a section. */
    private static void blank(BufferedWriter out) throws IOException {
        out.newLine();
    }

    private static void entry(BufferedWriter out, String key, Object value, boolean comma)
            throws IOException {
        // Strings need quoting; numbers and booleans must not be quoted, or they come back as text
        // on the next read.
        String written = value instanceof String text
                ? "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                : String.valueOf(value);
        out.write("  \"" + key + "\": " + written + (comma ? "," : ""));
        out.newLine();
    }

    /** Path shown to humans, trimmed to the game directory so log lines stay short. */
    private static String display(Path file) {
        Path configDir = Services.platform().configDir();
        Path parent = configDir.getParent();
        try {
            return parent == null ? file.toString() : parent.relativize(file).toString();
        } catch (IllegalArgumentException e) {
            return file.toString();
        }
    }
}

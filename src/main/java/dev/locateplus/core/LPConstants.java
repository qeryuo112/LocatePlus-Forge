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

/** Default values and hard limits in one place. */
public final class LPConstants {

    private LPConstants() {
    }

    public static final String MOD_ID = "locateplus";

    /** Folder name under {@code config/}. Hyphenated to match the jar and the docs. */
    public static final String MOD_ID_PATH = "locate-plus";
    public static final String MOD_NAME = "Locate Plus";

    /**
     * Default permission level: 2, the vanilla cheat level. Override with {@code permission_level}.
     */
    public static final int DEFAULT_PERMISSION_LEVEL = 2;

    // ---- Radius defaults / limits -------------------------------------------------------------

    /** Default radius, in blocks, for {@code /locate block} and {@code /locate entity}. */
    public static final int DEFAULT_BLOCK_RADIUS = 64;

    /** Upper bound accepted by the radius argument, in blocks. */
    public static final int MAX_BLOCK_RADIUS = 16_000;

    /** Default radius, in chunks, for {@code /lp analyze}. */
    public static final int DEFAULT_CHUNK_RADIUS = 4;

    /** Sanity ceiling on a chunk radius, see {@link #MAX_BLOCK_RADIUS}. */
    public static final int MAX_CHUNK_RADIUS = 1_000;

    /**
     * Lowest Y a scan reads, before the world's own floor is applied.
     *
     * The overworld figure. Every dimension is clamped to its real bounds at scan time, so this
     * default means the bottom of the world wherever it is used.
     */
    public static final int SCAN_MIN_Y = -64;

    /**
     * Highest Y a scan reads, before the world's own ceiling is applied.
     *
     * The overworld build limit. Clamped per dimension the same way {@link #SCAN_MIN_Y} is.
     */
    public static final int SCAN_MAX_Y = 320;

    /** Widest Y a config file may ask for, generous enough for any modded dimension. */
    public static final int SCAN_Y_BOUND = 8_192;

    /**
     * What a chat teleport button does when clicked.
     *
     * Filling the chat box is the default because a teleport moves you somewhere you may not want
     * to go, and a misclick in a long result list is easy. The command lands ready to send, so
     * confirming it costs one keypress.
     */
    public static final String TELEPORT_BUTTON_MODE = "suggest";

    /**
     * Which teleport a chat button uses.
     *
     * Safe by default: a result can name a block inside stone or over a drop, and vanilla
     * {@code /tp} would put you inside it. Set to false for a plain {@code /tp} that goes exactly
     * where the result says.
     */
    public static final boolean RESULT_BUTTON_SAFE_TELEPORT = true;

    /**
     * Which teleport the button on a biome, structure or point of interest result uses.
     *
     * Kept separate from {@link #RESULT_BUTTON_SAFE_TELEPORT} because these name a place rather than a
     * block. The coordinate can be open air, the inside of a wall, or the middle of a structure,
     * so the safe search matters more here than on a block that was seen directly.
     */
    public static final boolean BIOME_BUTTON_SAFE_TELEPORT = true;

    /**
     * Whether a teleport still happens when nowhere safe was found.
     *
     * On by default: the search only fails where there is nothing to stand on for a long way in
     * every direction, and refusing to move at all leaves you where you were with no way to get
     * there. Going anyway puts you exactly where you asked, the same as vanilla, and says so.
     * Set to false to have the teleport cancelled instead.
     */
    public static final boolean SAFETP_GO_ANYWAY = true;

    // ---- Performance --------------------------------------------------------------------------

    /**
     * Nanoseconds of server-thread time all scan jobs combined may consume per tick. A tick is 50
     * ms; 8 ms keeps headroom so scans never visibly lag the server.
     */
    public static final long TICK_BUDGET_NANOS = 8_000_000L;

    /**
     * Chunk count above which a force-load scan is called out as very large in chat.
     *
     * Purely advisory. The scan still runs, the warning states the real numbers so the decision is
     * an informed one rather than a blocked one.
     */
    public static final int FORCELOAD_WARN_THRESHOLD = 4_225; // 65x65

    /**
     * Maximum individual coordinates retained for an export. Beyond this the export is marked
     * truncated rather than risking an out-of-memory kill on a big forceload scan.
     */
    public static final int MAX_EXPORT_POSITIONS = 2_000_000;

    // ---- Presentation -------------------------------------------------------------------------

    /**
     * How many results chat shows before collapsing the rest into a count.
     *
     * The client keeps a hundred chat messages, so a longer report scrolls its own heading out of
     * view. Sixty covers essentially every scan without truncating.
     */
    public static final int CHAT_TOP_N = 60;

    /**
     * Whether {@code /locate block} outlines its nearest match.
     *
     * The outline is a display entity, so anyone nearby can see it. Turn it off on a shared server
     * where a search should not show others what was found, or if the outline is simply unwanted.
     */
    public static final boolean MARK_LOCATED_BLOCKS = true;

    /**
     * How long a glowing block outline lasts, in seconds. Zero means until something removes it.
     */
    public static final int BLOCK_MARKER_SECONDS = 60;

    /** Block the marker is made of. */
    public static final String BLOCK_MARKER_FILL = "minecraft:glass";

    /**
     * Colour of the marker outline, as RRGGBB.
     *
     * Cyan by default. It is the one hue that appears almost nowhere in terrain, so the outline
     * never blends into stone, dirt, grass, sand or lava.
     */
    public static final String BLOCK_MARKER_COLOUR = "#00E5FF";

    /** How long the nearest entity glows. */
    public static final int GLOW_DURATION_TICKS = 20 * 60;

    // ---- Item search --------------------------------------------------------------------------

    /**
     * How deep {@code /locate item} follows a container inside a container.
     *
     * A shulker box in a chest is depth 1, a shulker box inside a bundle inside a chest is depth 2.
     * Bounded because nothing stops a modded item from nesting into itself, which would otherwise
     * recurse until the stack overflows.
     */
    public static final int MAX_ITEM_NESTING_DEPTH = 4;

    /**
     * Cap on individual item locations recorded per scan.
     *
     * Separate from {@link #MAX_EXPORT_POSITIONS} because an item hit carries far more than a
     * packed long: a position, a count, a container description and a source label.
     */
    public static final int MAX_ITEM_HITS = 200_000;
}
